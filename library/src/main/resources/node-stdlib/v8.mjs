// Minimal `v8` module stub, served by Mycelium as the synthetic core module resolved for
// `import ... from "v8"` / `"node:v8"`. Real Node's `v8` module introspects the actual V8
// engine (heap snapshots, cached-data tags, code coverage); Mycelium runs on GraalJS/the
// JVM instead of V8, so there's no real V8 to introspect — `getHeapStatistics()` and
// friends are backed by `java.lang.Runtime`/`java.lang.management` JVM heap info where a
// reasonable mapping exists, and everything else (serialize/deserialize, snapshots,
// coverage, CPU profiling) is a shape-only stub.

const JRuntime = Java.type("java.lang.Runtime");

export function getHeapStatistics() {
    const rt = JRuntime.getRuntime();
    const totalHeap = Number(rt.totalMemory());
    const freeHeap = Number(rt.freeMemory());
    const maxHeap = Number(rt.maxMemory());
    return {
        total_heap_size: totalHeap,
        total_heap_size_executable: 0,
        total_physical_size: totalHeap,
        total_available_size: maxHeap - (totalHeap - freeHeap),
        used_heap_size: totalHeap - freeHeap,
        heap_size_limit: maxHeap,
        malloced_memory: 0,
        peak_malloced_memory: 0,
        does_zap_garbage: 0,
        number_of_native_contexts: 1,
        number_of_detached_contexts: 0,
        total_global_handles_size: 0,
        used_global_handles_size: 0,
        external_memory: 0,
    };
}

export function getHeapSpaceStatistics() {
    return [];
}

export function getHeapCodeStatistics() {
    return {
        code_and_metadata_size: 0,
        bytecode_and_metadata_size: 0,
        external_script_source_size: 0,
        cpu_profiler_metadata_size: 0,
    };
}

export function getCppHeapStatistics() {
    return {};
}

export function cachedDataVersionTag() {
    return 0;
}

export function setFlagsFromString(flags) {
    // no-op: there's no V8 to pass flags to.
}

export function getHeapSnapshot() {
    throw new Error("mycelium: v8.getHeapSnapshot() is not supported (no V8 heap to snapshot)");
}

export function writeHeapSnapshot(filename) {
    throw new Error("mycelium: v8.writeHeapSnapshot() is not supported (no V8 heap to snapshot)");
}

export function takeCoverage() {
    // no-op
}

export function stopCoverage() {
    // no-op
}

// Real Node's `v8.serialize`/`Serializer`/`Deserializer` use V8's structured-clone wire
// format (handles Maps, Sets, typed arrays, circular refs, Dates, RegExps, etc., and the
// resulting bytes are NOT JSON — they carry a distinct binary tag/version header). Mycelium
// runs on GraalJS/the JVM, not V8, so there is no real V8 serializer to call into. An
// earlier version of this file silently substituted `JSON.stringify`/`JSON.parse`, which
// LOOKS like it works for plain-object test cases but silently corrupts (or outright drops)
// anything JSON can't represent — Maps, Sets, circular references, Dates (turned into
// strings, not revived), undefined values, etc. That's worse than an error: code round-
// tripping a Map through `v8.serialize`/`deserialize` would silently get `{}` back with no
// indication anything went wrong. Throwing here is the honest behavior until a real
// structured-clone-compatible implementation exists.
const V8_SERIALIZE_UNSUPPORTED_MESSAGE =
    "mycelium: v8.serialize()/deserialize() are not supported — Mycelium runs on GraalJS, " +
    "not V8, so there is no V8 structured-clone wire format to produce/consume here. An " +
    "earlier version silently substituted JSON.stringify/JSON.parse, which corrupts Maps, " +
    "Sets, circular references, Dates, and other non-JSON-representable values without any " +
    "indication of data loss; throwing instead of silently corrupting data.";

export class Serializer {
    constructor() {
        this._chunks = [];
    }
    writeHeader() {}
    writeValue(value) {
        throw new Error(V8_SERIALIZE_UNSUPPORTED_MESSAGE);
    }
    releaseBuffer() {
        throw new Error(V8_SERIALIZE_UNSUPPORTED_MESSAGE);
    }
}

export class Deserializer {
    constructor(buffer) {
        this._buffer = buffer;
    }
    readHeader() {
        throw new Error(V8_SERIALIZE_UNSUPPORTED_MESSAGE);
    }
    readValue() {
        throw new Error(V8_SERIALIZE_UNSUPPORTED_MESSAGE);
    }
}

export class DefaultSerializer extends Serializer {}
export class DefaultDeserializer extends Deserializer {}

export function serialize(value) {
    throw new Error(V8_SERIALIZE_UNSUPPORTED_MESSAGE);
}

export function deserialize(buffer) {
    throw new Error(V8_SERIALIZE_UNSUPPORTED_MESSAGE);
}

export const promiseHooks = {
    onInit() { return () => {}; },
    onSettled() { return () => {}; },
    onBefore() { return () => {}; },
    onAfter() { return () => {}; },
    createHook() { return { stop() {} }; },
};

export function queryObjects() {
    return [];
}

export const startupSnapshot = {
    addSerializeCallback() {},
    addDeserializeCallback() {},
    setDeserializeMainFunction() {},
    isBuildingSnapshot() {
        return false;
    },
};

export function setHeapSnapshotNearHeapLimit(limit) {
    // no-op
}

export class GCProfiler {
    start() {}
    stop() {
        return {};
    }
}

export function isStringOneByteRepresentation(str) {
    for (let i = 0; i < str.length; i++) {
        if (str.charCodeAt(i) > 0xff) return false;
    }
    return true;
}

export function startCpuProfile() {
    throw new Error("mycelium: v8.startCpuProfile() is not supported");
}

export default {
    getHeapStatistics,
    getHeapSpaceStatistics,
    getHeapCodeStatistics,
    getCppHeapStatistics,
    cachedDataVersionTag,
    setFlagsFromString,
    getHeapSnapshot,
    writeHeapSnapshot,
    takeCoverage,
    stopCoverage,
    Serializer,
    Deserializer,
    DefaultSerializer,
    DefaultDeserializer,
    serialize,
    deserialize,
    promiseHooks,
    queryObjects,
    startupSnapshot,
    setHeapSnapshotNearHeapLimit,
    GCProfiler,
    isStringOneByteRepresentation,
    startCpuProfile,
};
