## Benji play demo app

This is a sample application to show how to integrate the benji's library
in a play application. This app uses compile time dependency injection.

This example uses the following:

- Benji: an object storage library
- Play! Framework v2.6.11 as a web framework
- Benji play plugin
- Benji VFS plugin as the demo uses the VFS storage


### Setup and run

To run the application:
```bash
sbt run
```

This application exposes a REST API.
You can open a browser and go to `localhost:9000`. To fully use 
the application, you can use Postman and 
import the [postman json file](benji_postman.json) 
to discover all the features described below.

### Features

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
    - Get a raw object
    - Delete objects
