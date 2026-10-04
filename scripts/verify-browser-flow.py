#!/usr/bin/env python3
"""Real same-origin API flow. Only an explicitly disposable loopback stack is allowed."""
import http.cookiejar, json, os, secrets, time, urllib.request, urllib.error, urllib.parse, uuid
base=os.environ.get('BASE_URL','http://127.0.0.1:3000').rstrip('/')
if not __debug__: raise SystemExit('Run without Python optimization so all checks remain active')
if os.environ.get('GATEFLOW_DISPOSABLE_STACK')!='1': raise SystemExit('Requires disposable stack consent')
u=urllib.parse.urlsplit(base)
if not (u.scheme=='http' and u.hostname in ('127.0.0.1','localhost') and not u.username and not u.password and not u.query and not u.fragment and u.path in ('','/')): raise SystemExit('Loopback only')
checks=0
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl): return None
class Client:
    def __init__(self):
        self.jar=http.cookiejar.CookieJar()
        self.http=urllib.request.build_opener(NoRedirect(),urllib.request.HTTPCookieProcessor(self.jar))
    def expect(self,path,status,method='GET',body=None,csrf=True,key=None):
        global checks
        headers={}
        if method!='GET' and csrf:
            c=self.call('/api/v1/auth/csrf');headers[c['headerName']]=c['token']
        if body is not None:headers['Content-Type']='application/json'
        if key:headers['Idempotency-Key']=key
        req=urllib.request.Request(base+path,None if body is None else json.dumps(body).encode(),headers,method=method)
        try:
            with self.http.open(req,timeout=20) as response:actual=response.status
        except urllib.error.HTTPError as e:actual=e.code
        assert actual==status,f'{method} {path.split("?")[0]}: expected {status}, got {actual}'
        checks+=1
    def call(self,path,method='GET',body=None,key=None):
        global checks
        headers={}
        if method!='GET':
            csrf=self.call('/api/v1/auth/csrf');headers[csrf['headerName']]=csrf['token']
        if body is not None: headers['Content-Type']='application/json'
        if key:headers['Idempotency-Key']=key
        req=urllib.request.Request(base+path,None if body is None else json.dumps(body).encode(),headers,method=method)
        try:
            with self.http.open(req,timeout=20) as response:
                checks+=1;data=response.read();return json.loads(data) if data else None
        except urllib.error.HTTPError as e:
            # Deliberately omit cookies, payloads, tokens, and credentials from logs.
            raise AssertionError(f'{method} {path.split("?")[0]} returned {e.code}') from None
anonymous=Client()
anonymous.expect('/api/v1/auth/me',401)
backend_base=os.environ.get('BACKEND_BASE_URL','http://127.0.0.1:8080').rstrip('/')
bu=urllib.parse.urlsplit(backend_base)
assert bu.scheme=='http' and bu.hostname in ('127.0.0.1','localhost') and bu.path in ('','/') and not bu.username and not bu.password and not bu.query and not bu.fragment,'Backend loopback only'
try:urllib.request.urlopen(backend_base+'/actuator/prometheus',timeout=10)
except urllib.error.HTTPError as e:assert e.code==401
else:raise AssertionError('Anonymous backend metrics unexpectedly allowed')
checks+=1
anonymous.expect('/api/v1/auth/login',403,'POST',{'email':'missing@example.invalid','password':'invalid'},csrf=False)
admin,reviewer=Client(),Client();suffix=uuid.uuid4().hex[:12];password='Gf-'+secrets.token_urlsafe(24)
admin.call('/api/v1/auth/signup','POST',{'email':f'admin-{suffix}@example.invalid','password':password,'displayName':'CI requester'})
r=reviewer.call('/api/v1/auth/signup','POST',{'email':f'reviewer-{suffix}@example.invalid','password':password,'displayName':'CI reviewer'})
o=admin.call('/api/v1/organizations','POST',{'name':'Disposable verification','slug':f'ci-{suffix}'});prefix=f'/api/v1/organizations/{o["id"]}'
role=admin.call(prefix+'/roles','POST',{'code':'CI_REVIEWER','name':'CI reviewer','permissions':['WORKFLOW_VIEW','REQUEST_APPROVE','REQUEST_VIEW_ALL']})
admin.call(prefix+'/memberships','POST',{'userId':r['id'],'roleIds':[role['id']]})
d=admin.call(prefix+'/workflows','POST',{'name':'CI approval','description':'Disposable same-origin flow'})
v=admin.call(prefix+f'/workflows/{d["id"]}/versions','POST',{'steps':[{'name':'Review','approverRoleId':role['id'],'condition':{'type':'ALWAYS'}}]})
v=admin.call(prefix+f'/workflows/{d["id"]}/versions/{v["id"]}/publish','POST',{'expectedVersion':v['version']})
body={'workflowVersionId':v['id'],'title':'CI laptop','description':'Verify real lifecycle','requestType':'PURCHASE','purchaseAmount':100,'currency':'USD','details':{'vendor':'CI supplier'}}
key=str(uuid.uuid4());request=admin.call(prefix+'/requests','POST',body,key)
replayed=admin.call(prefix+'/requests','POST',body,key);assert replayed['id']==request['id']
items=reviewer.call(prefix+'/requests/inbox?limit=20&pagination=OFFSET&offset=0')['items'];assert any(x['id']==request['id'] for x in items)
# Tenant and permission boundaries must be enforced server-side.
other=admin.call('/api/v1/organizations','POST',{'name':'Isolated second tenant','slug':f'other-{suffix}'})
reviewer.expect(f'/api/v1/organizations/{other["id"]}/requests?limit=20&pagination=OFFSET&offset=0',404)
reviewer.expect(prefix+'/audit-logs?limit=20&offset=0',403)
reviewer.expect(prefix+'/roles',403,method='POST',body={'code':'ESCALATE','name':'Forbidden','permissions':['ROLE_MANAGE']})
admin.expect(prefix+'/requests',409,'POST',{**body,'title':'Conflicting idempotency payload'},key=key)
step=next(s for s in request['steps'] if s['state']=='ACTIVE')
result=reviewer.call(prefix+f'/requests/{request["id"]}/steps/{step["id"]}/decisions','POST',{'expectedVersion':request['version'],'decision':'APPROVE','comment':'CI verified'},str(uuid.uuid4()));assert result['state']=='APPROVED'
items=admin.call(prefix+'/requests?limit=20&pagination=OFFSET&offset=0&status=APPROVED&q=laptop')['items'];assert any(x['id']==request['id'] for x in items)
assert admin.call(prefix+'/audit-logs?limit=20&offset=0')['items']
p=reviewer.call(prefix+'/notifications/preferences');reviewer.call(prefix+'/notifications/preferences','PUT',{'inAppEnabled':True,'emailEnabled':False,'expectedVersion':p['version']})
for _ in range(30):
    inbox=admin.call(prefix+'/notifications?limit=20&offset=0')['items']
    notice=next((x for x in inbox if x['requestId']==request['id']),None)
    if notice:break
    time.sleep(1)
else:raise AssertionError('Notification subscriber did not project within 30 seconds')
admin.call(prefix+f'/notifications/{notice["id"]}/read','PATCH')
admin.call(prefix+f'/requests/{request["id"]}/activity?limit=20&offset=0')
reviewer.expect(prefix+f'/requests/{request["id"]}/steps/{step["id"]}/decisions',409,'POST',{'expectedVersion':request['version'],'decision':'APPROVE','comment':'Stale duplicate'},key=str(uuid.uuid4()))
admin.call('/api/v1/auth/logout','POST')
admin.call('/api/v1/auth/login','POST',{'email':f'admin-{suffix}@example.invalid','password':password})
assert admin.call('/api/v1/auth/me')['email']==f'admin-{suffix}@example.invalid'
admin.call('/api/v1/auth/logout','POST')
admin.expect('/api/v1/auth/me',401)
# Optional isolated-load fixture: opaque session values are private, never logged/artifact-uploaded.
fixture=os.environ.get('GATEFLOW_LOAD_FIXTURE')
if fixture:
    import pathlib
    admin.call('/api/v1/auth/login','POST',{'email':f'admin-{suffix}@example.invalid','password':password})
    reviewer.call('/api/v1/auth/login','POST',{'email':f'reviewer-{suffix}@example.invalid','password':password})
    target=pathlib.Path(fixture)
    fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as f:json.dump({'base':base,'prefix':prefix,'body':body,'requestId':request['id'],'adminCookies':{c.name:c.value for c in admin.jar},'reviewerCookies':{c.name:c.value for c in reviewer.jar}},f)
print(f'PASS: {checks} successful HTTP checks; signup, RBAC, publish, submit/replay, review, search, audit, notifications, activity, logout')
