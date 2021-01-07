# Riff - Raft in your browser!

The point of this repository was just to explore a thought of immediate feedback, approaches to testing, as well as just some exporation of [Scala 3](https://dotty.epfl.ch/), [ZIO](https://zio.dev/) and [ScalaJS](https://www.scala-js.org/).

The idea is to explore what it might be like to do meaningful testing of distributed computation, but where bringing cluster nodes up and down is as 
straight-forward as opening/closing a browser tab! 🎉

And so, you can actually run this repo's code [right here](https://aaronp.github.io/riffd) (though currently only browsers w/ BroadcastChannel are supported until I polyfill that ... sorry Safari/IE users!)

![demo](docs/demo.gif)

By leveraging multi-platform compilation to drive development and testing, back-end developers can enjoy similar benefits front-end developers enjoy by running JVM server code directly in your browser.

# Inventing On Principal

The first inspiration for this project goes back to Bret Victor's amazing [Inventing On Principle talk](https://vimeo.com/36579366) talk in 2012.

I think we're all familiar with the concept of immediate feedback and ease of testability. I wanted to take a real-world example (a RAFT [consensus algorithm](consensus.md) implementation) that has to deal with failures, availability and randomness in order to provide both leader election and a consistent replicated log within a cluster.

As other applications typically build upon distributed commit logs and leadership election, it's feasible to conceive of other downstream projects which might use a similar approach to demonstrate, test and explore how their distributed application behaves, particularly when scaled or in the presence of failures.

Not only that, with a simple `sbt ~fastOptJS` call developers can immediately see their server-side changes as they tinker with them in a browser.

# Accelerate - do testing w/o the need for an integrated environment

This idea had festered for a while, but I was reminded of it more recently when I read Chapter 5 on Architecture in the __[Accelerate](https://www.amazon.co.uk/Accelerate-Software-Performing-Technology-Organizations/dp/1942788339)__ book:

> __Focus On Deployability And Testability__
>
> Although in most cases the type of system you are building is not important in terms of achieving high performance, two _architectural characteristics_ are.
> Those who agreed with the following statements were more likely to be in the high-performing group:
>  * We can do most of our testing without requiring an integrated environment
>  * We can and do deploy or release our application independently of other applications/services it depends on

I thnk this speaks to those questions.

Again, this project is meant just to serve as an example, demonstration, and taking point for those principles.

I have yet to apply the idea of multi-platform builds in a real-world project, but how great would it be to take advantage 
both of the huge ecosystem and uncountable hours in the Java space dedicated to tooling, tuning, monitoring, libraries, security, etc, 
but also have fun, immediately-testable feedback during development by running (and debugging) that code in a browser?

# Building/Demo

Again, don't even need to clone this repo as you can [run this code now here](https://aaronp.github.io/riffd) in a supported browser.

For instructions on building locally though, see [here](building.md)
