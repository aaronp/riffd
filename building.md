
# Building

This project is built using [sbt](https://www.scala-sbt.org/1.x/docs/Setup.html) on the [jvm](https://sdkman.io/usage), though produces both
jvm and [js](https://nodejs.org/en/) artifacts.

As the most-recent built artifacts are committed to './docs/demo/*', you can immediately run the code in your browser just by opening open `./docs/demo/index.html`:
```
open ./docs/demo/index.html
```

## Local Development

You can update the github demo page using a custom `sbt demo` task (which just compiles the relevant code copies it to ./docs/demo/), then manually commit/push those changes:

```
sbt demo
git commit -am 'made some updates'
git push origin
```

Typically, however, you would want to immediately compile/update the local site as you make changes, in which case
you would tell sbt to continually compile the javascript code (e.g. the 'riffJS' subproject) and then open/refresh `./local-test.html`:

```
open ./local-test.html <-- open this in a browser
sbt
project riffJS
~fastOptJS
```

[back](README.md)