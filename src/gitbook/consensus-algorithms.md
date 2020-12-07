---
description: One way of getting several machines to work together
---

# Consensus Algorithms

For those who haven't heard of/come across the RAFT protocol before, you'd be hard-pressed to find a more visually appealing/understandable explanation than with ["the secret lives of data"](http://thesecretlivesofdata.com/raft/) and [the RAFT specification.](https://raft.github.io/)

If you haven't, please have a look - and don't let the idea of reading a specification put you off. A primary goal of RAFT is to be understandable/readable, and the great job they've done surely accounts for the plethora of implementations in myriad languages.

### What problem are "consensus algorithms" trying to solve?

The general idea is that many of today's compute problems are better solved by having multiple computers working together to solve them. 

The general idea of spreading data and work across machines is the bedrock of modern responsive systems which can cope with individual server crashes/failures, network partitions, etc.

Like everything else, having multiple machines solves some problems while introducing others. For example, how do we get multiple machines to work together to solve a problem? Interesting thought experiments such as the [Two Generals' Problem](https://en.wikipedia.org/wiki/Two_Generals%27_Problem) provide good examples of coordination problems, and as it turns out, one good way to address the issue is to have one machine be the "Leader" and coordinate a consistent view-of-the-world to any number of "Followers". 

Put another way, one way to resolve Bob, Sue and George arguing about a particular fact is to nominate Sue as the "Leader" and just have Bob and George do as Sue says.

### 

### 

