package riff.js.ui

import riff.{Input, RaftNodeState}


/**
 * We hook into our input loop, capturing the before/afture state
 *
 * @param input
 * @param from
 * @param to
 */
case class Delta(input: Input, from: RaftNodeState, to: RaftNodeState)
