hospital-core/.../com/example/hms/service/impl/HospitalLifecycleStatusServiceImpl.java
Make this final field static too.

Intentionality
Maintainability


3
Low
convention
Open
Tiego Ouedraogo
Tiego Ouedraogo
L34
2min effort
2 hours ago
Code Smell
Minor
hospital-core/.../hms/service/integration/health/IntegrationHealthRecorder.java
Call transactional methods via an injected dependency instead of directly via 'this'.

Consistency
Maintainability


4
High
No tags
Open
Tiego Ouedraogo
Tiego Ouedraogo
L70
5min effort
1 hour ago
Code Smell
Critical
Call transactional methods via an injected dependency instead of directly via 'this'.

Consistency
Maintainability


4
High
No tags
Open
Tiego Ouedraogo
Tiego Ouedraogo
L92
5min effort
1 hour ago
Code Smell
Critical
hospital-core/.../service/integration/partner/StubPartnerConnector.java
This block of commented-out lines of code should be removed.

Intentionality
Maintainability


2
Medium
unused
Open
Tiego Ouedraogo
Tiego Ouedraogo
L44
5min effort
1 hour ago
Code Smell
Major
hospital-core/.../example/hms/service/scheduled/TenantPurgeExecutor.java
Define a constant instead of duplicating this literal "system:tenant-purge-job" 3 times.

Adaptability
Maintainability


4
High
design
Open
Tiego Ouedraogo
Tiego Ouedraogo
L120
8min effort
1 hour ago
Code Smell
Critical
Define a constant instead of duplicating this literal "ORGANIZATION" 3 times.

Adaptability
Maintainability


4
High
design
Open
Tiego Ouedraogo
Tiego Ouedraogo
L127
8min effort
1 hour ago
Code Smell
Critical
hospital-core/.../com/example/hms/service/tenant/TenantExportPackager.java
Define a constant instead of duplicating this literal "lifecycle_state" 3 times.

Adaptability
Maintainability


4
High
design
Open
Tiego Ouedraogo
Tiego Ouedraogo
L112
8min effort
1 hour ago
Code Smell
Critical
hospital-core/.../com/example/hms/service/impl/AuditSavedSearchServiceImplTest.java
Refactor the code of the lambda to have only one invocation possibly throwing a runtime exception.

Intentionality
Maintainability


2
Medium
junit
tests
Open
Tiego Ouedraogo
Tiego Ouedraogo
L107
5min effort
2 hours ago
Code Smell
Major
Refactor the code of the lambda to have only one invocation possibly throwing a runtime exception.

Intentionality
Maintainability


2
Medium
junit
tests
Open
Tiego Ouedraogo
Tiego Ouedraogo
L134
5min effort
2 hours ago
Code Smell
Major
Refactor the code of the lambda to have only one invocation possibly throwing a runtime exception.

Intentionality
Maintainability


2
Medium
junit
tests
Open
Tiego Ouedraogo
Tiego Ouedraogo
L164
5min effort
2 hours ago
Code Smell
Major
Refactor the code of the lambda to have only one invocation possibly throwing a runtime exception.

Intentionality
Maintainability


2
Medium
junit
tests
Open
Tiego Ouedraogo
Tiego Ouedraogo
L175
5min effort
2 hours ago
Code Smell
Major
hospital-core/.../com/example/hms/service/impl/SubscriptionFeatureGateServiceImplTest.java
Replace these 3 tests with a single Parameterized one.

Consistency
Maintainability


2
Medium
bad-practice
clumsy
...
Open
Tiego Ouedraogo
Tiego Ouedraogo
L179
10min effort
2 hours ago
Code Smell
Major
hospital-core/.../hms/service/integration/health/IntegrationHealthActionServiceTest.java
Refactor the code of the lambda to have only one invocation possibly throwing a runtime exception.

Intentionality
Maintainability


2
Medium
junit
tests
Open
Tiego Ouedraogo
Tiego Ouedraogo
L102
5min effort
1 hour ago
Code Smell
Major





Duplicated Lines (%) on New Code
2.1%
Duplicated Lines (%) on New Code
Duplicated Lines on New Code

hospital-core/src/main/java/com/example/hms/model/Hospital.java
63.0%
29

hospital-core/src/main/java/com/example/hms/payload/dto/superadmin/HospitalLifecycleResponseDTO.java
35.3%
18

hospital-core/src/main/java/com/example/hms/service/impl/HospitalLifecycleStatusServiceImpl.java
20.8%
15

hospital-core/src/main/java/com/example/hms/service/impl/HospitalLifecycleServiceImpl.java
10.7%
34
