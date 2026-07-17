package org.mycelium.ptc

/**
 * Sentinel returned by a patched `JSFunctionCallNode.executeCall` in place of actually
 * performing a call, whenever that particular call site is executing in *tail position*
 * (see [TailCallTransformer]'s class doc comment for the full mechanism). Carries the raw
 * JS call-arguments array in GraalJS's own packed `JSArguments` layout — `[functionObject,
 * thisOrNewTarget, userArg0, userArg1, ...]` — completely unevaluated further, so the
 * trampoline loop spliced into `FunctionRootNode.executeInRealm` (see
 * [TailCallTransformer.patchFunctionRootNode]) can decide what to do with it: reuse the
 * current Java stack frame for a self-recursive call, or fall back to a real call for
 * anything else.
 *
 * The constructor descriptor here — `([Ljava/lang/Object;)V` — is load-bearing: the
 * bytecode spliced into `JSFunctionCallNode.executeCall` constructs one of these with a
 * raw `NEW`/`DUP`/`INVOKESPECIAL` sequence, so this class's shape must stay exactly this
 * simple (single `Object[]` field, no validation, no other constructors) or that splice
 * needs to change to match.
 */
class TailCall(@JvmField val args: Array<Any?>)
