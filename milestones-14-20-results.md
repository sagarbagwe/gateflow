# Milestones 14-20 verification

- Source commit: `a8580d25f9464b0cda76159a5972b82c9f66269a`
- Completed: 2026-10-03T11:08:01.804277+00:00
- Runner: GitHub-hosted Ubuntu with Java 21, Node 22, Docker and k6

| Gate | Outcome |
|---|---|
| M14 frontend lint/build/tests | **SUCCESS** |
| M20 production dependency audit | **SUCCESS** |
| M16 Compose validation | **SUCCESS** |
| M16 production image builds | **SUCCESS** |
| M16 full-stack runtime smoke | **SUCCESS** |
| M15 health/metrics access contract | **SUCCESS** |
| M19 k6 performance smoke | **SUCCESS** |
| M17/M18/M20 CI, AWS, and security evidence | **SUCCESS** |

All gates must be SUCCESS before Milestones 14-20 are considered verified.

## dependency audit output (last 60 lines)

```text
found 0 vulnerabilities
```

## full-stack smoke output (last 60 lines)

```text
#33 naming to docker.io/library/gateflow-frontend done
#33 DONE 0.0s

#34 [frontend] resolving provenance for metadata file
#34 DONE 0.0s
 frontend  Built
 backend  Built
 Network gateflow_default  Creating
 Network gateflow_default  Created
 Volume "gateflow_postgres_data"  Creating
 Volume "gateflow_postgres_data"  Created
 Volume "gateflow_rabbitmq_data"  Creating
 Volume "gateflow_rabbitmq_data"  Created
 Container gateflow-mailpit-1  Creating
 Container gateflow-rabbitmq-1  Creating
 Container gateflow-postgres-1  Creating
 Container gateflow-redis-1  Creating
 Container gateflow-mailpit-1  Created
 Container gateflow-redis-1  Created
 Container gateflow-postgres-1  Created
 Container gateflow-rabbitmq-1  Created
 Container gateflow-backend-1  Creating
 Container gateflow-backend-1  Created
 Container gateflow-frontend-1  Creating
 Container gateflow-frontend-1  Created
 Container gateflow-mailpit-1  Starting
 Container gateflow-postgres-1  Starting
 Container gateflow-redis-1  Starting
 Container gateflow-rabbitmq-1  Starting
 Container gateflow-redis-1  Started
 Container gateflow-postgres-1  Started
 Container gateflow-rabbitmq-1  Started
 Container gateflow-mailpit-1  Started
 Container gateflow-mailpit-1  Waiting
 Container gateflow-postgres-1  Waiting
 Container gateflow-redis-1  Waiting
 Container gateflow-rabbitmq-1  Waiting
 Container gateflow-mailpit-1  Healthy
 Container gateflow-redis-1  Healthy
 Container gateflow-postgres-1  Healthy
 Container gateflow-rabbitmq-1  Healthy
 Container gateflow-backend-1  Starting
 Container gateflow-backend-1  Started
 Container gateflow-backend-1  Waiting
 Container gateflow-backend-1  Healthy
 Container gateflow-frontend-1  Starting
 Container gateflow-frontend-1  Started
 Container gateflow-mailpit-1  Waiting
 Container gateflow-backend-1  Waiting
 Container gateflow-frontend-1  Waiting
 Container gateflow-postgres-1  Waiting
 Container gateflow-redis-1  Waiting
 Container gateflow-rabbitmq-1  Waiting
 Container gateflow-mailpit-1  Healthy
 Container gateflow-postgres-1  Healthy
 Container gateflow-backend-1  Healthy
 Container gateflow-redis-1  Healthy
 Container gateflow-rabbitmq-1  Healthy
 Container gateflow-frontend-1  Healthy
PASS: full GateFlow stack is healthy
```

## performance smoke output (last 60 lines)

```text

running (2m49.0s), 08/20 VUs, 1778 complete and 0 interrupted iterations
default   [  94% ] 08/20 VUs  2m49.0s/3m00.0s

running (2m50.0s), 08/20 VUs, 1786 complete and 0 interrupted iterations
default   [  94% ] 08/20 VUs  2m50.0s/3m00.0s

running (2m51.0s), 07/20 VUs, 1794 complete and 0 interrupted iterations
default   [  95% ] 07/20 VUs  2m51.0s/3m00.0s

running (2m52.0s), 06/20 VUs, 1801 complete and 0 interrupted iterations
default   [  96% ] 06/20 VUs  2m52.0s/3m00.0s

running (2m53.0s), 06/20 VUs, 1807 complete and 0 interrupted iterations
default   [  96% ] 06/20 VUs  2m53.0s/3m00.0s

running (2m54.0s), 05/20 VUs, 1813 complete and 0 interrupted iterations
default   [  97% ] 05/20 VUs  2m54.0s/3m00.0s

running (2m55.0s), 04/20 VUs, 1818 complete and 0 interrupted iterations
default   [  97% ] 04/20 VUs  2m55.0s/3m00.0s

running (2m56.0s), 04/20 VUs, 1822 complete and 0 interrupted iterations
default   [  98% ] 04/20 VUs  2m56.0s/3m00.0s

running (2m57.0s), 03/20 VUs, 1826 complete and 0 interrupted iterations
default   [  98% ] 03/20 VUs  2m57.0s/3m00.0s

running (2m58.0s), 02/20 VUs, 1829 complete and 0 interrupted iterations
default   [  99% ] 02/20 VUs  2m58.0s/3m00.0s

running (2m59.0s), 02/20 VUs, 1831 complete and 0 interrupted iterations
default   [  99% ] 02/20 VUs  2m59.0s/3m00.0s

running (3m00.0s), 01/20 VUs, 1833 complete and 0 interrupted iterations
default   [ 100% ] 01/20 VUs  3m00.0s/3m00.0s

     ✓ csrf available

     checks.........................: 100.00% 1834 out of 1834
     data_received..................: 1.2 MB  6.5 kB/s
     data_sent......................: 176 kB  974 B/s
     http_req_blocked...............: avg=9.24µs  min=2.04µs   med=4.75µs  max=2ms      p(90)=6.51µs  p(95)=7.9µs   
     http_req_connecting............: avg=3.11µs  min=0s       med=0s      max=1.77ms   p(90)=0s      p(95)=0s      
   ✓ http_req_duration..............: avg=2.46ms  min=845.12µs med=2.08ms  max=30.17ms  p(90)=4.11ms  p(95)=4.92ms  
       { expected_response:true }...: avg=2.46ms  min=845.12µs med=2.08ms  max=30.17ms  p(90)=4.11ms  p(95)=4.92ms  
   ✓ http_req_failed................: 0.00%   0 out of 1834
     http_req_receiving.............: avg=78.91µs min=14.96µs  med=68.59µs max=564.81µs p(90)=122.8µs p(95)=155.03µs
     http_req_sending...............: avg=20.11µs min=5.47µs   med=19.77µs max=415.65µs p(90)=24.85µs p(95)=29.39µs 
     http_req_tls_handshaking.......: avg=0s      min=0s       med=0s      max=0s       p(90)=0s      p(95)=0s      
     http_req_waiting...............: avg=2.36ms  min=793.89µs med=1.97ms  max=30.04ms  p(90)=3.99ms  p(95)=4.79ms  
     http_reqs......................: 1834    10.148854/s
     iteration_duration.............: avg=1s      min=1s       med=1s      max=1.04s    p(90)=1s      p(95)=1s      
     iterations.....................: 1834    10.148854/s
     vus............................: 1       min=1            max=20
     vus_max........................: 20      min=20           max=20


running (3m00.7s), 00/20 VUs, 1834 complete and 0 interrupted iterations
default ✓ [ 100% ] 00/20 VUs  3m0s
```
