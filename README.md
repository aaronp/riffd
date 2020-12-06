---
description: >-
  An example of spike-and-stabilize, pragmatic and visual feedback applied to a
  distributed compute problem
---

# Riff Raft

This is a project I've been meaning to write for a long time.

It's the culmination of several lines of thought:

* [my views on testing](i-hate-tdd.md)
* [consensus algorithms](consensus-algorithms.md) / [why I like solving this problem](why-i-like-solving-this-problem/)
* taking [an immediate-feedback/spike stabilize/test-gen approach](why-i-like-solving-this-problem/how-to-do-it-right.md)

The **TL;DR** is that you can write server-side code which runs in the browser, enabling you \(and your team\) to play with/test your code, and use those manual/visual interactions to generate your tests \(either when you want to protect correct code from regressions or demonstrate incorrect/unexpected behaviour\).

I thought an example using distributed compute \(e.g. a consensus algorithm\) would be a nifty example, because typically distributed systems' behaviour requires potentially slow or tedious integration tests.

This example makes starting/stopping nodes as easy as opening browser tabs. And this isn't just a toy -- the code being run in your browser is derived from the same source code which gets compiled to an efficient, type-safe binary artefact which you run your servers on!



