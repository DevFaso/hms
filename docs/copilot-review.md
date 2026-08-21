You reached the start of the range
2026-08-21 08:38
2026-08-21 12:38:49  INFO [main] com.example.hms.HmsApplication           - Starting HmsApplication v0.0.1-SNAPSHOT using Java 21.0.11 with PID 5 (/app/app.jar started by appuser in /app)
2026-08-21 12:38:49 DEBUG [main] com.example.hms.HmsApplication           - Running with Spring Boot v3.4.13, Spring v6.2.15
2026-08-21 12:38:49  INFO [main] com.example.hms.HmsApplication           - The following 1 profile is active: "dev"
2026-08-21 12:38:50  INFO [main] .s.d.r.c.RepositoryConfigurationDelegate - Multiple Spring Data modules found, entering strict repository configuration mode
2026-08-21 12:38:50  INFO [main] .s.d.r.c.RepositoryConfigurationDelegate - Bootstrapping Spring Data JPA repositories in DEFAULT mode.
2026-08-21 12:38:51  INFO [main] .s.d.r.c.RepositoryConfigurationDelegate - Finished Spring Data repository scanning in 558 ms. Found 169 JPA repository interfaces.
2026-08-21 12:38:53  INFO [main] o.s.b.w.embedded.tomcat.TomcatWebServer  - Tomcat initialized with port 8080 (http)
2026-08-21 12:38:53  INFO [main] o.apache.catalina.core.StandardService   - Starting service [Tomcat]
2026-08-21 12:38:53  INFO [main] o.apache.catalina.core.StandardEngine    - Starting Servlet engine: [Apache Tomcat/10.1.50]
2026-08-21 12:38:53  INFO [main] o.a.c.c.C.[Tomcat].[localhost].[/api]    - Initializing Spring embedded WebApplicationContext
2026-08-21 12:38:53  INFO [main] w.s.c.ServletWebServerApplicationContext - Root WebApplicationContext: initialization completed in 4239 ms
2026-08-21 12:38:53  INFO [main] ca.uhn.fhir.util.VersionUtil             - HAPI FHIR version 7.4.5 - Rev 43a4bd4529
2026-08-21 12:38:53  INFO [main] ca.uhn.fhir.context.FhirContext          - Creating new FHIR context for FHIR version [R4]
2026-08-21 12:38:53  INFO [main] com.zaxxer.hikari.HikariDataSource       - hms-primary-pool - Starting...
2026-08-21 12:38:54  INFO [main] com.zaxxer.hikari.pool.HikariPool        - hms-primary-pool - Added connection org.postgresql.jdbc.PgConnection@4447c594
2026-08-21 12:38:54  INFO [main] com.zaxxer.hikari.HikariDataSource       - hms-primary-pool - Start completed.
2026-08-21 12:38:54  INFO [main] liquibase.database                       - Set default schema name to public
2026-08-21 12:38:54  INFO [main] liquibase.changelog                      - Reading from public.databasechangelog
2026-08-21 12:38:55  INFO [main] liquibase.ui                             - Database is up to date, no changesets to execute
2026-08-21 12:38:55  INFO [main] liquibase.changelog                      - Reading from public.databasechangelog
2026-08-21 12:38:55  INFO [main] liquibase.util                           - UPDATE SUMMARY
2026-08-21 12:38:55  INFO [main] liquibase.util                           - Run:                          0
2026-08-21 12:38:55  INFO [main] liquibase.util                           - Previously run:             114
2026-08-21 12:38:55  INFO [main] liquibase.util                           - Filtered out:                 0
2026-08-21 12:38:55  INFO [main] liquibase.util                           - -------------------------------
2026-08-21 12:38:55  INFO [main] liquibase.util                           - Total change sets:          114
2026-08-21 12:38:55  INFO [main] liquibase.util                           - Update summary generated
2026-08-21 12:38:55  INFO [main] liquibase.command                        - Command execution complete
2026-08-21 12:38:55  INFO [main] o.hibernate.jpa.internal.util.LogHelper  - HHH000204: Processing PersistenceUnitInfo [name: default]
2026-08-21 12:38:55  INFO [main] org.hibernate.Version                    - HHH000412: Hibernate ORM core version 6.6.39.Final
2026-08-21 12:38:55  INFO [main] o.h.c.internal.RegionFactoryInitiator    - HHH000026: Second-level cache disabled
2026-08-21 12:38:55  INFO [main] org.hibernate.orm.connections.pooling    - HHH10001005: Database info:
	Database JDBC URL [Connecting through datasource 'HikariDataSource (hms-primary-pool)']
	Database driver: undefined/unknown
	Database version: 17.10
	Autocommit mode: undefined/unknown
	Isolation level: undefined/unknown
	Minimum pool size: undefined/unknown
	Maximum pool size: undefined/unknown
2026-08-21 12:38:55  INFO [main] o.s.o.j.p.SpringPersistenceUnitInfo      - No LoadTimeWeaver setup: ignoring JPA class transformer
2026-08-21 12:38:56  WARN [main] o.h.boot.model.internal.ToOneBinder      - HHH000491: 'com.example.hms.model.User.patientProfile' uses both @NotFound and FetchType.LAZY. @ManyToOne and @OneToOne associations mapped with @NotFound are forced to EAGER fetching.
2026-08-21 12:38:56  WARN [main] o.h.boot.model.internal.ToOneBinder      - HHH000491: 'com.example.hms.model.User.staffProfile' uses both @NotFound and FetchType.LAZY. @ManyToOne and @OneToOne associations mapped with @NotFound are forced to EAGER fetching.
2026-08-21 12:39:00  INFO [main] o.h.e.t.j.p.i.JtaPlatformInitiator       - HHH000489: No JTA platform available (set 'hibernate.transaction.jta.platform' to enable JTA platform integration)
2026-08-21 12:39:00 ERROR [main] j.LocalContainerEntityManagerFactoryBean - Failed to initialize JPA EntityManagerFactory: [PersistenceUnit: default] Unable to build Hibernate SessionFactory; nested exception is org.hibernate.tool.schema.spi.SchemaManagementException: Schema-validation: missing column [critical_escalation_level] in table [lab.lab_results]
2026-08-21 12:39:00 ERROR [main] o.s.b.web.embedded.tomcat.TomcatStarter  - Error starting Tomcat context. Exception: org.springframework.beans.factory.UnsatisfiedDependencyException. Message: Error creating bean with name 'fhirServletRegistration' defined in class path resource [com/example/hms/fhir/FhirConfig.class]: Unsatisfied dependency expressed through method 'fhirServletRegistration' parameter 1: Error creating bean with name 'conditionFhirResourceProvider' defined in URL [jar:nested:/app/app.jar/!BOOT-INF/classes/!/com/example/hms/fhir/provider/ConditionFhirResourceProvider.class]: Unsatisfied dependency expressed through constructor parameter 0: Error creating bean with name 'patientProblemRepository' defined in com.example.hms.repository.PatientProblemRepository defined in @EnableJpaRepositories declared on TenantRepositoryConfig: Cannot resolve reference to bean 'jpaSharedEM_entityManagerFactory' while setting bean property 'entityManager'
2026-08-21 12:39:00  INFO [main] o.apache.catalina.core.StandardService   - Stopping service [Tomcat]
2026-08-21 12:39:00  WARN [main] o.a.c.loader.WebappClassLoaderBase       - The web application [api] appears to have started a thread named [hms-primary-pool housekeeper] but has failed to stop it. This is very likely to create a memory leak. Stack trace of thread:
 java.base/jdk.internal.misc.Unsafe.park(Native Method)
 java.base/java.util.concurrent.locks.LockSupport.parkNanos(Unknown Source)
 java.base/java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject.awaitNanos(Unknown Source)
 java.base/java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.take(Unknown Source)
 java.base/java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.take(Unknown Source)
 java.base/java.util.concurrent.ThreadPoolExecutor.getTask(Unknown Source)
 java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(Unknown Source)
 java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(Unknown Source)
 java.base/java.lang.Thread.run(Unknown Source)
2026-08-21 12:39:00  WARN [main] ConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.context.ApplicationContextException: Unable to start web server
2026-08-21 12:39:00  INFO [main] com.zaxxer.hikari.HikariDataSource       - hms-primary-pool - Shutdown initiated...
2026-08-21 12:39:00  INFO [main] com.zaxxer.hikari.HikariDataSource       - hms-primary-pool - Shutdown completed.
2026-08-21 12:39:00  INFO [main] .s.b.a.l.ConditionEvaluationReportLogger - 
Error starting ApplicationContext. To display the condition evaluation report re-run your application with 'debug' enabled.
2026-08-21 12:39:00 ERROR [main] o.s.boot.SpringApplication               - Application run failed
org.springframework.context.ApplicationContextException: Unable to start web server
	at org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext.onRefresh(ServletWebServerApplicationContext.java:170)
	at org.springframework.context.support.AbstractApplicationContext.refresh(AbstractApplicationContext.java:621)
	at org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext.refresh(ServletWebServerApplicationContext.java:146)
	at org.springframework.boot.SpringApplication.refresh(SpringApplication.java:752)
	at org.springframework.boot.SpringApplication.refreshContext(SpringApplication.java:439)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:318)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:1361)
	at org.springframework.boot.SpringApplication.run(SpringApplication.java:1350)
	at com.example.hms.HmsApplication.main(HmsApplication.java:20)
	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(Unknown Source)
	at java.base/java.lang.reflect.Method.invoke(Unknown Source)
	at org.springframework.boot.loader.launch.Launcher.launch(Launcher.java:102)
	at org.springframework.boot.loader.launch.Launcher.launch(Launcher.java:64)
	at org.springframework.boot.loader.launch.JarLauncher.main(JarLauncher.java:40)
Caused by: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'fhirServletRegistration' defined in class path resource [com/example/hms/fhir/FhirConfig.class]: Unsatisfied dependency expressed through method 'fhirServletRegistration' parameter 1: Error creating bean with name 'conditionFhirResourceProvider' defined in URL [jar:nested:/app/app.jar/!BOOT-INF/classes/!/com/example/hms/fhir/provider/ConditionFhirResourceProvider.class]: Unsatisfied dependency expressed through constructor parameter 0: Error creating bean with name 'patientProblemRepository' defined in com.example.hms.repository.PatientProblemRepository defined in @EnableJpaRepositories declared on TenantRepositoryConfig: Cannot resolve reference to bean 'jpaSharedEM_entityManagerFactory' while setting bean property 'entityManager'
Caused by: org.springframework.boot.web.server.WebServerException: Unable to start embedded Tomcat
	at org.springframework.boot.web.embedded.tomcat.TomcatWebServer.initialize(TomcatWebServer.java:147)
	at org.springframework.boot.web.embedded.tomcat.TomcatWebServer.<init>(TomcatWebServer.java:107)
	at org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory.getTomcatWebServer(TomcatServletWebServerFactory.java:517)
	at org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory.getWebServer(TomcatServletWebServerFactory.java:219)
	at org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext.createWebServer(ServletWebServerApplicationContext.java:193)
	at org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext.onRefresh(ServletWebServerApplicationContext.java:167)
	... 13 common frames omitted
	at org.springframework.beans.factory.support.ConstructorResolver.createArgumentArray(ConstructorResolver.java:804)
	at org.springframework.beans.factory.support.ConstructorResolver.instantiateUsingFactoryMethod(ConstructorResolver.java:546)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.instantiateUsingFactoryMethod(AbstractAutowireCapableBeanFactory.java:1375)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBeanInstance(AbstractAutowireCapableBeanFactory.java:1205)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.doCreateBean(AbstractAutowireCapableBeanFactory.java:569)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBean(AbstractAutowireCapableBeanFactory.java:529)
	at org.springframework.beans.factory.support.AbstractBeanFactory.lambda$doGetBean$0(AbstractBeanFactory.java:339)
	at org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.getSingleton(DefaultSingletonBeanRegistry.java:373)
	at org.springframework.beans.factory.support.AbstractBeanFactory.doGetBean(AbstractBeanFactory.java:337)
	at org.springframework.beans.factory.support.AbstractBeanFactory.getBean(AbstractBeanFactory.java:207)
	at org.springframework.boot.web.servlet.ServletContextInitializerBeans.getOrderedBeansOfType(ServletContextInitializerBeans.java:211)
	at org.springframework.boot.web.servlet.ServletContextInitializerBeans.getOrderedBeansOfType(ServletContextInitializerBeans.java:202)
	at org.springframework.boot.web.servlet.ServletContextInitializerBeans.addServletContextInitializerBeans(ServletContextInitializerBeans.java:97)
	at org.springframework.boot.web.servlet.ServletContextInitializerBeans.<init>(ServletContextInitializerBeans.java:86)
	at org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext.getServletContextInitializerBeans(ServletWebServerApplicationContext.java:271)
	at org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext.selfInitialize(ServletWebServerApplicationContext.java:245)
	at org.springframework.boot.web.embedded.tomcat.TomcatStarter.onStartup(TomcatStarter.java:52)
	at org.apache.catalina.core.StandardContext.startInternal(StandardContext.java:4452)
	at org.apache.catalina.util.LifecycleBase.start(LifecycleBase.java:164)
	at org.apache.catalina.core.ContainerBase$StartChild.call(ContainerBase.java:1201)
	at org.apache.catalina.core.ContainerBase$StartChild.call(ContainerBase.java:1191)
	at java.base/java.util.concurrent.FutureTask.run(Unknown Source)
	at org.apache.tomcat.util.threads.InlineExecutorService.execute(InlineExecutorService.java:81)
	at java.base/java.util.concurrent.AbstractExecutorService.submit(Unknown Source)
	at org.apache.catalina.core.ContainerBase.startInternal(ContainerBase.java:747)
	at org.apache.catalina.core.StandardHost.startInternal(StandardHost.java:783)
	at org.apache.catalina.util.LifecycleBase.start(LifecycleBase.java:164)
	at org.apache.catalina.core.ContainerBase$StartChild.call(ContainerBase.java:1201)
	at org.apache.catalina.util.LifecycleBase.start(LifecycleBase.java:164)
	at org.apache.catalina.core.ContainerBase$StartChild.call(ContainerBase.java:1191)
	at java.base/java.util.concurrent.FutureTask.run(Unknown Source)
	at org.apache.catalina.core.StandardServer.startInternal(StandardServer.java:868)
	at org.apache.tomcat.util.threads.InlineExecutorService.execute(InlineExecutorService.java:81)
	at org.apache.catalina.util.LifecycleBase.start(LifecycleBase.java:164)
	at java.base/java.util.concurrent.AbstractExecutorService.submit(Unknown Source)
	at org.apache.catalina.startup.Tomcat.start(Tomcat.java:436)
	at org.apache.catalina.core.ContainerBase.startInternal(ContainerBase.java:747)
	at org.springframework.boot.web.embedded.tomcat.TomcatWebServer.initialize(TomcatWebServer.java:128)
	at org.apache.catalina.core.StandardEngine.startInternal(StandardEngine.java:201)
	... 18 common frames omitted
	at org.apache.catalina.util.LifecycleBase.start(LifecycleBase.java:164)
Caused by: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'conditionFhirResourceProvider' defined in URL [jar:nested:/app/app.jar/!BOOT-INF/classes/!/com/example/hms/fhir/provider/ConditionFhirResourceProvider.class]: Unsatisfied dependency expressed through constructor parameter 0: Error creating bean with name 'patientProblemRepository' defined in com.example.hms.repository.PatientProblemRepository defined in @EnableJpaRepositories declared on TenantRepositoryConfig: Cannot resolve reference to bean 'jpaSharedEM_entityManagerFactory' while setting bean property 'entityManager'
	at org.apache.catalina.core.StandardService.startInternal(StandardService.java:410)
	at org.springframework.beans.factory.support.ConstructorResolver.createArgumentArray(ConstructorResolver.java:804)
	at org.springframework.beans.factory.support.ConstructorResolver.autowireConstructor(ConstructorResolver.java:240)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.autowireConstructor(AbstractAutowireCapableBeanFactory.java:1395)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBeanInstance(AbstractAutowireCapableBeanFactory.java:1232)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.doCreateBean(AbstractAutowireCapableBeanFactory.java:569)
	at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBean(AbstractAutowireCapableBeanFactory.java:529)
	at org.springframework.beans.factory.support.AbstractBeanFactory.lambda$doGetBean$0(AbstractBeanFactory.java:339)
