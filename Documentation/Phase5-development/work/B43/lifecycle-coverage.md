# B43 instrumented Lifecycle coverage

The approved JaCoCo `0.8.16` agent instrumented `com.company.requests.domain.Lifecycle` in the exact published B24 checkout `e550379a754a686b85903d233c9d7d2061bd5d7a`. Pinned Temurin `25.0.5.1+2` and Maven `3.9.16` ran the existing `LifecycleTest` through the domain reactor in strict offline mode. All 156 cases passed. The reporter used the exact class from that run. [Owner approval and pins](coverage-approval.md) · [Maven transcript](../../../../tmp/phase5-development/b43-tools/lifecycle-e550379/maven-lifecycle.log)

| Selected method | Covered branches | Missed branches | Cyclomatic complexity |
| --- | ---: | ---: | ---: |
| `nextFromStart` | 2 | 0 | 2 |
| `next` | 2 | 0 | 2 |
| `transition` | 5 | 0 | 4 |

The three methods measured 100% branch coverage and complexity at or below the reference ceiling of 10. This measures the three approved methods in the existing local test suite. The broader critical-path selection and release gate retain their separate dispositions. [JaCoCo XML](../../../../tmp/phase5-development/b43-tools/lifecycle-e550379/Lifecycle.xml) · [Selected counter extract](../../../../tmp/phase5-development/b43-tools/lifecycle-e550379/three-methods.json) · [JaCoCo counters](https://www.jacoco.org/jacoco/trunk/doc/counters.html)

| Artifact | SHA-256 |
| --- | --- |
| `Lifecycle.class` | `1edaeca805f6d6204533a65b623d6fa210fd051697b876c17c6e1a0855e8d955` |
| `Lifecycle.exec` | `b1083b538a9f77173bec8f9af713ca5bf7d9b8d659891a82fdc54b8c8c9d846a` |
| `Lifecycle.xml` | `a6068c1e53529ecd82f388341fbc5933c1f810afd37de85199ba844509b21d96` |
| `three-methods.json` | `3e12b1087e92ad9015e89c0d160beaea0dea778c40c24bc4c16b5a0468d1352b` |
| Maven transcript | `2da45d5b7cf4a3ee16592cee6654873fee309dee9045da1b4d9f82e8bd83bab9` |

The test command selected `-pl domain -Dtest=LifecycleTest` and set Surefire `argLine` to `-javaagent:<verified-agent>=destfile=<scratch/Lifecycle.exec>,append=false,includes=com.company.requests.domain.Lifecycle`. The reporter command was `java -jar <verified-cli> report <scratch/Lifecycle.exec> --classfiles <exact Lifecycle.class> --xml <scratch/Lifecycle.xml>`. Use a fresh output directory for repeat runs.
