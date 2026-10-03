# Milestones 14-20 verification

- Source commit: `0670514217b781bb7b261d4e27c9f07f39f3b3df`
- Completed: 2026-10-03T11:00:08.409327+00:00
- Runner: GitHub-hosted Ubuntu with Java 21, Node 22, Docker and k6

| Gate | Outcome |
|---|---|
| M14 frontend lint/build/tests | **SUCCESS** |
| M20 production dependency audit | **SUCCESS** |
| M16 Compose validation | **SUCCESS** |
| M16 production image builds | **SUCCESS** |
| M16 full-stack runtime smoke | **FAILURE** |
| M15 health/metrics access contract | **SUCCESS** |
| M19 k6 performance smoke | **FAILURE** |
| M17/M18/M20 CI, AWS, and security evidence | **SUCCESS** |

All gates must be SUCCESS before Milestones 14-20 are considered verified.

## dependency audit output (last 60 lines)

```text
found 0 vulnerabilities
```

## full-stack smoke output (last 60 lines)

```text
backend-1   |   '  |____| .__|_| |_|_| |_\__, | / / / /
backend-1   |  =========|_|==============|___/=/_/_/_/
backend-1   | 
backend-1   |  :: Spring Boot ::               (v3.5.16)
backend-1   | 
backend-1   | 2026-10-03T10:56:49.910Z  INFO 1 --- [gateflow] [           main] com.gateflow.GateFlowApplication         : Starting GateFlowApplication v0.1.0-SNAPSHOT using Java 21.0.6 with PID 1 (/app/app.jar started by gateflow in /app)
backend-1   | 2026-10-03T10:56:49.915Z  INFO 1 --- [gateflow] [           main] com.gateflow.GateFlowApplication         : No active profile set, falling back to 1 default profile: "default"
backend-1   | 2026-10-03T10:56:51.944Z  INFO 1 --- [gateflow] [           main] .s.d.r.c.RepositoryConfigurationDelegate : Multiple Spring Data modules found, entering strict repository configuration mode
backend-1   | 2026-10-03T10:56:51.946Z  INFO 1 --- [gateflow] [           main] .s.d.r.c.RepositoryConfigurationDelegate : Bootstrapping Spring Data JPA repositories in DEFAULT mode.
backend-1   | 2026-10-03T10:56:52.132Z  INFO 1 --- [gateflow] [           main] .s.d.r.c.RepositoryConfigurationDelegate : Finished Spring Data repository scanning in 175 ms. Found 1 JPA repository interface.
backend-1   | 2026-10-03T10:56:52.972Z  INFO 1 --- [gateflow] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat initialized with port 8080 (http)
backend-1   | 2026-10-03T10:56:52.991Z  INFO 1 --- [gateflow] [           main] o.apache.catalina.core.StandardService   : Starting service [Tomcat]
backend-1   | 2026-10-03T10:56:52.991Z  INFO 1 --- [gateflow] [           main] o.apache.catalina.core.StandardEngine    : Starting Servlet engine: [Apache Tomcat/10.1.55]
backend-1   | 2026-10-03T10:56:53.033Z  INFO 1 --- [gateflow] [           main] o.a.c.c.C.[Tomcat].[localhost].[/]       : Initializing Spring embedded WebApplicationContext
backend-1   | 2026-10-03T10:56:53.035Z  INFO 1 --- [gateflow] [           main] w.s.c.ServletWebServerApplicationContext : Root WebApplicationContext: initialization completed in 3018 ms
backend-1   | 2026-10-03T10:56:53.503Z  INFO 1 --- [gateflow] [           main] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Starting...
backend-1   | 2026-10-03T10:56:53.758Z  INFO 1 --- [gateflow] [           main] com.zaxxer.hikari.pool.HikariPool        : HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection@33eab2e8
backend-1   | 2026-10-03T10:56:53.761Z  INFO 1 --- [gateflow] [           main] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Start completed.
backend-1   | 2026-10-03T10:56:53.784Z  INFO 1 --- [gateflow] [           main] org.flywaydb.core.FlywayExecutor         : Database: jdbc:postgresql:******** (PostgreSQL 17.11)
backend-1   | 2026-10-03T10:56:53.858Z  INFO 1 --- [gateflow] [           main] o.f.c.i.s.JdbcTableSchemaHistory         : Schema history table "public"."flyway_schema_history" does not exist yet
backend-1   | 2026-10-03T10:56:53.862Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbValidate     : Successfully validated 11 migrations (execution time 00:00.024s)
backend-1   | 2026-10-03T10:56:53.888Z  INFO 1 --- [gateflow] [           main] o.f.c.i.s.JdbcTableSchemaHistory         : Creating Schema History table "public"."flyway_schema_history" ...
backend-1   | 2026-10-03T10:56:53.955Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbMigrate      : Current version of schema "public": << Empty Schema >>
backend-1   | 2026-10-03T10:56:54.013Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "1 - create core schema"
backend-1   | 2026-10-03T10:56:54.093Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "2 - enforce workflow and audit integrity"
backend-1   | 2026-10-03T10:56:54.110Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "3 - seed permission catalog"
backend-1   | 2026-10-03T10:56:54.125Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "4 - create authentication storage"
backend-1   | 2026-10-03T10:56:54.144Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "5 - add rbac management metadata"
backend-1   | 2026-10-03T10:56:54.160Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "6 - add workflow commands"
backend-1   | 2026-10-03T10:56:54.182Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "7 - add request search"
backend-1   | 2026-10-03T10:56:54.201Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "8 - protect request cursor id"
backend-1   | 2026-10-03T10:56:54.219Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "9 - add transactional outbox"
backend-1   | 2026-10-03T10:56:54.251Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "10 - add notifications"
backend-1   | 2026-10-03T10:56:54.270Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "public" to version "11 - harden audit evidence"
backend-1   | 2026-10-03T10:56:54.280Z  INFO 1 --- [gateflow] [           main] o.f.core.internal.command.DbMigrate      : Successfully applied 11 migrations to schema "public", now at version v11 (execution time 00:00.137s)
backend-1   | 2026-10-03T10:56:54.383Z  INFO 1 --- [gateflow] [           main] o.hibernate.jpa.internal.util.LogHelper  : HHH000204: Processing PersistenceUnitInfo [name: default]
backend-1   | 2026-10-03T10:56:54.459Z  INFO 1 --- [gateflow] [           main] org.hibernate.Version                    : HHH000412: Hibernate ORM core version 6.6.53.Final
backend-1   | 2026-10-03T10:56:54.505Z  INFO 1 --- [gateflow] [           main] o.h.c.internal.RegionFactoryInitiator    : HHH000026: Second-level cache disabled
backend-1   | 2026-10-03T10:56:54.878Z  INFO 1 --- [gateflow] [           main] o.s.o.j.p.SpringPersistenceUnitInfo      : No LoadTimeWeaver setup: ignoring JPA class transformer
backend-1   | 2026-10-03T10:56:54.978Z  INFO 1 --- [gateflow] [           main] org.hibernate.orm.connections.pooling    : HHH10001005: Database info:
backend-1   | 	Database JDBC URL [Connecting through datasource 'HikariDataSource (HikariPool-1)']
backend-1   | 	Database driver: undefined/unknown
backend-1   | 	Database version: 17.11
backend-1   | 	Autocommit mode: undefined/unknown
backend-1   | 	Isolation level: undefined/unknown
backend-1   | 	Minimum pool size: undefined/unknown
backend-1   | 	Maximum pool size: undefined/unknown
backend-1   | 2026-10-03T10:56:55.998Z  INFO 1 --- [gateflow] [           main] o.h.e.t.j.p.i.JtaPlatformInitiator       : HHH000489: No JTA platform available (set 'hibernate.transaction.jta.platform' to enable JTA platform integration)
backend-1   | 2026-10-03T10:56:56.045Z  INFO 1 --- [gateflow] [           main] j.LocalContainerEntityManagerFactoryBean : Initialized JPA EntityManagerFactory for persistence unit 'default'
backend-1   | 2026-10-03T10:56:56.977Z  INFO 1 --- [gateflow] [           main] o.s.d.j.r.query.QueryEnhancerFactory     : Hibernate is in classpath; If applicable, HQL parser will be used.
backend-1   | 2026-10-03T10:56:59.506Z  INFO 1 --- [gateflow] [           main] o.s.b.a.e.web.EndpointLinksResolver      : Exposing 3 endpoints beneath base path '/actuator'
backend-1   | 2026-10-03T10:57:00.026Z  INFO 1 --- [gateflow] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port 8080 (http) with context path '/'
backend-1   | 2026-10-03T10:57:00.029Z  INFO 1 --- [gateflow] [           main] o.s.a.r.c.CachingConnectionFactory       : Attempting to connect to: [rabbitmq:5672]
backend-1   | 2026-10-03T10:57:00.078Z  INFO 1 --- [gateflow] [           main] o.s.a.r.c.CachingConnectionFactory       : Created new connection: rabbitConnectionFactory#71cdde0c:0/SimpleConnection@1368a7d9 [delegate=amqp://gateflow@172.18.0.3:5672/gateflow, localPort=40992]
backend-1   | 2026-10-03T10:57:00.292Z  INFO 1 --- [gateflow] [           main] com.gateflow.GateFlowApplication         : Started GateFlowApplication in 11.145 seconds (process running for 11.938)
backend-1   | 2026-10-03T10:57:03.807Z  INFO 1 --- [gateflow] [0.0-8080-exec-1] o.a.c.c.C.[Tomcat].[localhost].[/]       : Initializing Spring DispatcherServlet 'dispatcherServlet'
backend-1   | 2026-10-03T10:57:03.807Z  INFO 1 --- [gateflow] [0.0-8080-exec-1] o.s.web.servlet.DispatcherServlet        : Initializing Servlet 'dispatcherServlet'
backend-1   | 2026-10-03T10:57:03.809Z  INFO 1 --- [gateflow] [0.0-8080-exec-1] o.s.web.servlet.DispatcherServlet        : Completed initialization in 1 ms
backend-1   | 2026-10-03T10:57:03.951Z  INFO 1 --- [gateflow] [0.0-8080-exec-1] com.gateflow.http.RequestIdFilter        : request_id=cfbad27a-ae28-4043-892c-01b32262fadb method=GET path=<unmatched> status=200 duration_ms=115
backend-1   | 2026-10-03T10:57:05.082Z  INFO 1 --- [gateflow] [0.0-8080-exec-2] com.gateflow.http.RequestIdFilter        : request_id=0c6c6a86-9aed-4dd6-9e77-7f11ee18f6c3 method=GET path=<unmatched> status=200 duration_ms=6
```

## performance smoke output (last 60 lines)

```text

running (2m54.0s), 05/20 VUs, 1815 complete and 0 interrupted iterations
default   [  97% ] 05/20 VUs  2m54.0s/3m00.0s
time="2026-10-03T11:00:02Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"
time="2026-10-03T11:00:02Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"
time="2026-10-03T11:00:02Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"
time="2026-10-03T11:00:02Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"

running (2m55.0s), 04/20 VUs, 1820 complete and 0 interrupted iterations
default   [  97% ] 04/20 VUs  2m55.0s/3m00.0s
time="2026-10-03T11:00:03Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"
time="2026-10-03T11:00:03Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"
time="2026-10-03T11:00:03Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"

running (2m56.0s), 03/20 VUs, 1824 complete and 0 interrupted iterations
default   [  98% ] 03/20 VUs  2m56.0s/3m00.0s
time="2026-10-03T11:00:04Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"
time="2026-10-03T11:00:04Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"
time="2026-10-03T11:00:04Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"

running (2m57.0s), 03/20 VUs, 1827 complete and 0 interrupted iterations
default   [  98% ] 03/20 VUs  2m57.0s/3m00.0s
time="2026-10-03T11:00:05Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"
time="2026-10-03T11:00:05Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"

running (2m58.0s), 02/20 VUs, 1830 complete and 0 interrupted iterations
default   [  99% ] 02/20 VUs  2m58.0s/3m00.0s
time="2026-10-03T11:00:06Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"

running (2m59.0s), 01/20 VUs, 1832 complete and 0 interrupted iterations
default   [  99% ] 01/20 VUs  2m59.0s/3m00.0s
time="2026-10-03T11:00:07Z" level=warning msg="Request Failed" error="Get \"http://127.0.0.1:3000/api/v1/auth/csrf\": dial tcp 127.0.0.1:3000: connect: connection refused"

running (3m00.0s), 01/20 VUs, 1833 complete and 0 interrupted iterations
default   [ 100% ] 01/20 VUs  3m00.0s/3m00.0s

     ✗ csrf available
      ↳  0% — ✓ 0 / ✗ 1834

     checks.....................: 0.00%   0 out of 1834
     data_received..............: 0 B     0 B/s
     data_sent..................: 288 B   1.598557215151813 B/s
     http_req_blocked...........: avg=638ns min=0s med=0s max=592.96µs p(90)=0s p(95)=0s
     http_req_connecting........: avg=468ns min=0s med=0s max=506.72µs p(90)=0s p(95)=0s
   ✓ http_req_duration..........: avg=720ns min=0s med=0s max=439.72µs p(90)=0s p(95)=0s
   ✗ http_req_failed............: 100.00% 1834 out of 1834
     http_req_receiving.........: avg=0s    min=0s med=0s max=0s       p(90)=0s p(95)=0s
     http_req_sending...........: avg=123ns min=0s med=0s max=96.18µs  p(90)=0s p(95)=0s
     http_req_tls_handshaking...: avg=0s    min=0s med=0s max=0s       p(90)=0s p(95)=0s
     http_req_waiting...........: avg=596ns min=0s med=0s max=363.93µs p(90)=0s p(95)=0s
     http_reqs..................: 1834    10.179701/s
     iteration_duration.........: avg=1s    min=1s med=1s max=1s       p(90)=1s p(95)=1s
     iterations.................: 1834    10.179701/s
     vus........................: 1       min=1                 max=20
     vus_max....................: 20      min=20                max=20


running (3m00.2s), 00/20 VUs, 1834 complete and 0 interrupted iterations
default ✓ [ 100% ] 00/20 VUs  3m0s
time="2026-10-03T11:00:08Z" level=error msg="thresholds on metrics 'http_req_failed' have been crossed"
```
