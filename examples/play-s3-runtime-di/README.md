# Benji S3 Play demo

This is a sample application to show how to integrate the benji's library
in a play application. This app uses Guice to handle runtime dependency injection.

This example uses the following:

- Benji: an object storage library
- Play! Framework v2.7.0 as a web framework
- Benji play plugin
- Benji S3 plugin as the demo uses the S3 storage

## Setup and run

You have to configure a URI that indicates what kind of storage you are going to use. 
For instance for s3, you have to specify an URI of the form 
`s3:protocol://accessKey:secretKey@host?style=path"` with your own credentials.
To set this variable, you can do this in different ways:

- Use the environment variable "BENJI_URI"
- Setup the variable in the conf/application.conf
- Setup your own configuration that overrides the default configuration. 
To do that, you can create a new file in conf/ called local.conf and define your URI.  

To run the application:
```bash
sbt run
```

This application exposes a REST API.
You can open a browser and go to `localhost:9000`. To fully use 
the application, you can use Postman and 
import the [postman json file](benji_postman.json) 
to discover all the features described below.

## Features

This application covers the main features of the benji's library 
and how to use its DSL. Basically, it 
performs CRUD operations on buckets and objects :  

- Bucket:
    - List buckets
    - Create buckets
    - Delete buckets
- Object:
    - List objects
    - Create objects
    - Get object metadata
    - Get a raw object
    - Delete objects
