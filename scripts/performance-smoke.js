import http from 'k6/http';import{check,sleep}from'k6';
export const options={stages:[{duration:'30s',target:5},{duration:'2m',target:20},{duration:'30s',target:0}],thresholds:{http_req_failed:['rate<0.01'],http_req_duration:['p(95)<750']}};
const base=__ENV.BASE_URL||'http://127.0.0.1:3000';
export default function(){const r=http.get(`${base}/api/v1/auth/csrf`,{tags:{operation:'csrf'}});check(r,{'csrf available':x=>x.status===200});sleep(1)}
