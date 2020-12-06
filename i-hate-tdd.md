---
description: 'Spoiler: It seems to hurt more often than it helps'
---

# I hate TDD

Don't get me wrong, I've been doing consulting/contracting work for a long time. I respect the way my clients work, and will support them in delivering software using a test-driven approach. After all, there's always the possibility I've just been doing it wrong \(and in which case I'll appreciate the opportunity for my new Acme Client to show me the light\).

The problem though that I've found is that it doesn't come for free. It's a trade-off, and the value it brings can be vastly outweighed by the concessions made:

* It'll take longer to get real user feedback Writing tests first is like taking the waterfall approach to writing code. Instead, write some code and integrate it - get something end-to-end. You'll get a much better feel for how things hang-together.  
* The more time I spend writing tests, the more biased I'll be for the code it's testing If I have an exhaustive test suite, have N% code coverage, or have spent time \(even just an hour\) writing tests., the more my opinions will influenced by thinking about the business problem in terms of that code. 
* I'll end up with some test infrastructure which may influence how I write the application It's good to think about sound-engineering principles such as single-responsibility, modularity, concurrency, etc - but you can keep these things in mind as you write your first steel-thread/tracer-bullet code 
* You'll end up manually writing doing things you could get for-free Computers are very good at reading, writing and computing things. Go figure.  We still seem to insist on doing these things for them.

#### So what's the alternative?

Many people reading this will recognise a lot of similarity to Dan North's 2016 "Software, Faster" talk - and this piece being his ["Spike and Stabilise" approach](https://youtu.be/USc-yLHXNUg?t=964).  


This project is an example of something written using that approach. I wrote little unit tests if they helped me define/understand/ensure certain ideas I had would work, but I mainly was trying to get to something which I could expose to an end-user.

I also knew that my code was largely based on functional-programming concepts and [ZIO](https://github.com/zio/zio). I knew therefore that would get to a place where several pieces of code would have this shape:  
`SomeType => SomeInput => (SomeType, SomeInput)`

If I could have a test shim where I \(or users/testers\) could apply inputs and see the outputs, then I could manually/visually inspect several scenarios and either notice odd behaviour \*or\* correct behaviour. Either way, if I just 'wrote down' what the user/tester was doing, then I could use that to generate a test.







