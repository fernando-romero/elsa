# ELSA
Small Scala application that displays the public repositories of a given github user, and for each repository its contributors sorted in descending order by commits.

# Requirements
* Scala 2.12.6+.
* Sbt 1.2.0+.
* `GITHUB_OAUTH_TOKEN` environment variable set with a Github OAuth token.

# Run with Sbt
`sbt 'run USERNAME_HERE'`

# Run jar
First run `sbt assembly` and then `java -jar target/scala-2.12/elsa.jar USERNAME_HERE`

# Output
The output will be a pretty printed json in the terminal. Example:
```
{
  "username": "fernando-romero",
  "repositories": [{
    "name": "docker-compose-test",
    "contributors": [{
      "name": "fernando-romero",`
      "commits": 2
    }]
  }, {
    "name": "akka-link-resolver",
    "contributors": [{
      "name": "fernando-romero",
      "commits": 3
    }]
  }, {
    "name": "worldcuppolla",
    "contributors": [{
      "name": "fernando-romero",
      "commits": 1
    }]
  }, {
    "name": "monadeek",
    "contributors": [{
      "name": "sirthias",
      "commits": 70
    }, {
      "name": "jrudolph",
      "commits": 33
    }, {
      "name": "jcranky",
      "commits": 4
    }, {
      "name": "stillalex",
      "commits": 2
    }, {
      "name": "vmunier",
      "commits": 1
    }]
  }]
}
``

#Logging
In `application.conf` the log level of Akka can be set in order to debug the application.