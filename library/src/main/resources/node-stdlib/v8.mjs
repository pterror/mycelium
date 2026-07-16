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

export class Serializer {
    constructor() {
        this._chunks = [];
    }
    writeHeader() {}
    writeValue(value) {
        this._chunks.push(value);
        return true;
    }
    releaseBuffer() {
        return new Uint8Array(0);
    }
}

export class Deserializer {
    constructor(buffer) {
        this._buffer = buffer;
    }
    readHeader() {
        return true;
    }
    readValue() {
        return undefined;
    }
}

export class DefaultSerializer extends Serializer {}
export class DefaultDeserializer extends Deserializer {}

export function serialize(value) {
    // Real Node uses V8's structured-clone wire format. Approximated here via JSON, which
    // is enough for simple JSON-shaped values but doesn't round-trip everything V8's
    // serializer supports (Maps, Sets, circular refs, etc.).
    const json = JSON.stringify(value);
    const bytes = new Uint8Array(json.length);
    for (let i = 0; i < json.length; i++) bytes[i] = json.charCodeAt(i) & 0xff;
    return bytes;
}

export function deserialize(buffer) {
    let str = "";
    for (let i = 0; i < buffer.length; i++) str += String.fromCharCode(buffer[i]);
    return JSON.parse(str);
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
