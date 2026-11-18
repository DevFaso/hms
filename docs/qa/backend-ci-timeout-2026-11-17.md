# Backend CI timeout on PR 1197

Run 44019295151, head 08da883a9: shards 1 and 2 passed in 10m00s and
11m19s. Shard 0 reached the existing 30-minute job timeout. Its ward-scale
integration tests continued passing, but the order-by-order MAR equivalence
check ran from approximately 05:01:36 to 05:13 UTC; subsequent tests were still
running when cancellation occurred. The log also contains background pools
trying closed prior Testcontainers databases. No failed assertion was reported
before cancellation; that is not evidence that the unfinished suite passed.

Class: hashing a large integration suite into too few partitions can overload
one runner while other runners have finished. Use the already-supported Gradle
partitioner with four CI shards instead of three. Matrix, shard-count input and
required coverage-artifact count all become four together. Every top-level
class still hashes to exactly one shard, nested classes follow their owner,
and the final job still requires every shard and merges all JaCoCo files.

The 30-minute timeout, every test, assertion, performance query limit and the
80% coverage gate are unchanged. This is a workload distribution correction;
CI must confirm elapsed time on the new head. Application and billing behavior
are unchanged. Existing full-suite and portal validation are rerun before
publishing; the hosted PostgreSQL/Testcontainers run remains independent.


Also carry the same French price-list test isolation as #1196/#1198 into this
sibling branch: save and restore stored language and reset the formatter cache.
French price assertions remain strict. This removes dependence on randomized
suite order, without modifying production formatting or billing calculations.


## Class table and local verification

| Defect | Class / inventory | Correction | Guard / falsification | Evidence |
| --- | --- | --- | --- | --- |
| CI-TIMEOUT | Uneven integration workload in the backend workflow's matrix, Gradle shard-count argument and required coverage-artifact count | Change all three from 3 to 4, preserving class hash partitioning and aggregation | YAML parsed; matrix equals range of configured count; aggregation depends on all shards; 1,433 top-level test source classes partition exactly once (359/376/354/344). Old head reached 30-minute cancellation. | Local complete suites rerun; hosted timing remains to be confirmed on new head |
| CI-LOCALE | Price-list French assertions read shared stored/cached locale on the three sibling branches | Same save/set/reset/restore test fixture on #1197 as #1196/#1198 | Strict French assertions unchanged; full randomized portal suite | 5,580 portal tests and coverage floors passed; lint and formatting passed |

No entity, migration, production Java or production portal code changes are
included. Existing backend billing review fixes are retained from 08da883a9.
