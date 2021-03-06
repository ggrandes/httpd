# httpd

httpd is a simple HTTP Server in Java. Open Source project under Apache License v2.0

### Current Stable Version is [1.1.2](https://search.maven.org/#search|ga|1|g%3Aorg.javastack%20a%3Ahttpd)

---

## DOC

#### Usage Example (command line)

    java -jar httpd-x.x.x.jar <tcp-port> <directory|zipfile>

#### Usage Example (code)

```java
	HttpServer srv = new HttpServer(8080, "/srv/wwwroot/");
	srv.setReadTimeoutMillis(180000);
	srv.start();
	// ...
	srv.stop();
```

---

## MAVEN

Add the dependency to your pom.xml:

    <dependency>
        <groupId>org.javastack</groupId>
        <artifactId>httpd</artifactId>
        <version>1.1.2</version>
    </dependency>


---
Inspired in [Apache HTTPD](http://httpd.apache.org/), this code is Java-minimalistic version.
