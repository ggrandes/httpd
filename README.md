# httpd

httpd is a simple HTTP Server in Java. Open Source project under Apache License v2.0

### Current Stable Version is [1.2.0](https://search.maven.org/#search|ga|1|g%3Aorg.javastack%20a%3Ahttpd)

---

## DOC

#### Usage Example (command line)

    java -Dhttpd.access.enable=true -jar httpd-x.x.x.jar <tcp-port> <directory|zipfile|none>

##### System properties:

- httpd.test.enable: Enable internal test URI: /test (default: true)
- httpd.access.enable: Enable common access log to stdout (default: false)
- httpd.debug.enable: Enable debug traces (default: false)


#### Usage Example (code)

```java
	HttpServer srv = new HttpServer(8080, "/srv/wwwroot/");
	// Defaults:
	// srv.setAcceptBacklog(511);
	// srv.setAcceptTimeoutMillis(1000);
	// srv.setReadTimeoutMillis(60000);
	// srv.setTestEnabled(true);
	// srv.setAccessLogEnabled(false);
	// srv.setDebugLogEnabled(false);
	srv.start();
	// ...
	srv.stop();
```

#### Check internal test-URI (command line)

    curl -i http://localhost:8080/test

---

## MAVEN

Add the dependency to your pom.xml:

    <dependency>
        <groupId>org.javastack</groupId>
        <artifactId>httpd</artifactId>
        <version>1.2.0</version>
    </dependency>


---
Inspired in [Apache HTTPD](http://httpd.apache.org/), this code is Java-minimalistic version.
