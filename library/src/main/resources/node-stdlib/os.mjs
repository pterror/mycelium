// Minimal Node.js-compatible `os` module, served by Mycelium as the synthetic core
// module resolved for `import ... from "os"` / `"node:os"`. Backed by Java interop via
// `Java.type(...)` (GraalJS) since JS itself has no access to system/process info.

const JSystem = Java.type("java.lang.System");
const JRuntime = Java.type("java.lang.Runtime");
const JInetAddress = Java.type("java.net.InetAddress");

function osNameProp() {
    return String(JSystem.getProperty("os.name") || "");
}

export function platform() {
    const name = osNameProp().toLowerCase();
    if (name.includes("win")) return "win32";
    if (name.includes("mac") || name.includes("darwin")) return "darwin";
    if (name.includes("linux")) return "linux";
    return "linux";
}

export function arch() {
    const a = String(JSystem.getProperty("os.arch") || "").toLowerCase();
    if (a.includes("amd64") || a.includes("x86_64")) return "x64";
    if (a.includes("aarch64") || a.includes("arm64")) return "arm64";
    if (a.includes("x86") || a.includes("i386") || a.includes("i686")) return "ia32";
    return a;
}

export function homedir() {
    return String(JSystem.getProperty("user.home") || "");
}

export function tmpdir() {
    const dir = String(JSystem.getProperty("java.io.tmpdir") || "/tmp");
    return dir.endsWith("/") ? dir.slice(0, -1) : dir;
}

export function hostname() {
    try {
        return String(JInetAddress.getLocalHost().getHostName());
    } catch (e) {
        return "localhost";
    }
}

export function type() {
    const name = osNameProp().toLowerCase();
    if (name.includes("win")) return "Windows_NT";
    if (name.includes("mac") || name.includes("darwin")) return "Darwin";
    if (name.includes("linux")) return "Linux";
    return String(JSystem.getProperty("os.name") || "Unknown");
}

export function release() {
    return String(JSystem.getProperty("os.version") || "");
}

export function cpus() {
    const runtime = JRuntime.getRuntime();
    const count = Number(runtime.availableProcessors());
    const result = [];
    for (let i = 0; i < count; i++) {
        result.push({
            model: "unknown",
            speed: 0,
            times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 },
        });
    }
    return result;
}

export function totalmem() {
    return Number(JRuntime.getRuntime().totalMemory());
}

export function freemem() {
    return Number(JRuntime.getRuntime().freeMemory());
}

export function uptime() {
    try {
        const JManagementFactory = Java.type("java.lang.management.ManagementFactory");
        const uptimeMs = Number(JManagementFactory.getRuntimeMXBean().getUptime());
        return uptimeMs / 1000;
    } catch (e) {
        return 0;
    }
}

export const EOL = platform() === "win32" ? "\r\n" : "\n";

export function userInfo(options) {
    const username = String(JSystem.getProperty("user.name") || "");
    const shell = String(JSystem.getenv("SHELL") || null);
    return {
        username,
        uid: -1,
        gid: -1,
        shell: shell === "null" ? null : shell,
        homedir: homedir(),
    };
}

export function networkInterfaces() {
    // Simplified stub: real network interface enumeration isn't wired up yet.
    return {};
}

export function loadavg() {
    return [0, 0, 0];
}

export function endianness() {
    const JByteOrder = Java.type("java.nio.ByteOrder");
    return JByteOrder.nativeOrder().toString() === "BIG_ENDIAN" ? "BE" : "LE";
}

export const constants = {
    signals: {},
    errno: {},
};

export default {
    platform,
    arch,
    homedir,
    tmpdir,
    hostname,
    type,
    release,
    cpus,
    totalmem,
    freemem,
    uptime,
    EOL,
    userInfo,
    networkInterfaces,
    loadavg,
    endianness,
    constants,
};
