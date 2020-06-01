# Servicer
A simple annotation processor for wiring Java services. Simply enable annotation processing 
(processing is done via the `com.austinv11.servicer.ServicerProcessor` class) and annotate all your service implementations with the
`@WireService` annotation. Now, on compilation, your services will have their associated metadata generated so you can use Java's
`ServiceLoader` without needing to mess with messy resource files. 

This project has the same goal as [Google's AutoService](https://github.com/google/auto/tree/master/service) but this is much more 
lightweight (only includes 3 classes and has no external dependencies).

It also supports gradle's incremental compilation feature.

## Usage
Add this project to your pom.xml as so:
```xml
<dependency>
    <groupId>com.austinv11.servicer</groupId>
    <artifactId>Servicer</artifactId>
    <version>1.0.3</version>
    <optional>true</optional>
</dependency>
```

Or if you use gradle, add this project to your build.gradle as so:
```groovy
dependencies {
    compileOnly 'com.austinv11.servicer:Servicer:1.0.3'
    annotationProcessor 'com.austinv11.servicer:Servicer:1.0.3'
}
```

Then simply annotate service implementations with the `@WireService` annotation!
