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

// Reads `/proc/cpuinfo` on Linux for real per-core model name/speed. Returns `null` (not
// an empty array) when the file can't be read/parsed, so `cpus()` can fall back cleanly on
// non-Linux platforms or in restricted environments without pretending to have real data.
function readProcCpuinfo() {
    try {
        const JPaths = Java.type("java.nio.file.Paths");
        const JFiles = Java.type("java.nio.file.Files");
        const path = JPaths.get("/proc/cpuinfo");
        if (!JFiles.exists(path)) return null;
        const lines = String(JFiles.readString(path)).split("\n");
        const cores = [];
        let current = {};
        for (const line of lines) {
            if (line.trim() === "") {
                if (Object.keys(current).length > 0) cores.push(current);
                current = {};
                continue;
            }
            const idx = line.indexOf(":");
            if (idx === -1) continue;
            const key = line.slice(0, idx).trim();
            const value = line.slice(idx + 1).trim();
            if (key === "model name") current.model = value;
            else if (key === "cpu MHz") current.speed = Math.round(parseFloat(value));
        }
        if (Object.keys(current).length > 0) cores.push(current);
        return cores.length > 0 ? cores : null;
    } catch (e) {
        return null;
    }
}

// Reads per-CPU `user/nice/system/idle/iowait/irq/softirq` jiffy counters from
// `/proc/stat` (one "cpuN ..." line per logical core, in the same order `/proc/cpuinfo`
// lists "processor" entries) — used to populate each core's `times` field. Returns `null`
// on non-Linux/unreadable systems, same convention as readProcCpuinfo.
function readProcStatTimes() {
    try {
        const JPaths = Java.type("java.nio.file.Paths");
        const JFiles = Java.type("java.nio.file.Files");
        const path = JPaths.get("/proc/stat");
        if (!JFiles.exists(path)) return null;
        const lines = String(JFiles.readString(path)).split("\n");
        const perCore = [];
        for (const line of lines) {
            const m = /^cpu(\d+)\s+(.*)$/.exec(line);
            if (!m) continue;
            const fields = m[2].trim().split(/\s+/).map(Number);
            const [user, nice, sys, idle, iowait, irq, softirq] = fields;
            perCore[Number(m[1])] = {
                user: user || 0,
                nice: nice || 0,
                sys: sys || 0,
                idle: idle || 0,
                irq: (irq || 0) + (softirq || 0),
            };
        }
        return perCore.length > 0 ? perCore : null;
    } catch (e) {
        return null;
    }
}

export function cpus() {
    const runtime = JRuntime.getRuntime();
    const count = Number(runtime.availableProcessors());
    const cpuinfo = platform() === "linux" ? readProcCpuinfo() : null;
    const stat = platform() === "linux" ? readProcStatTimes() : null;
    const result = [];
    for (let i = 0; i < count; i++) {
        const info = cpuinfo && cpuinfo[i];
        const times = (stat && stat[i]) || { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 };
        result.push({
            model: info && info.model ? info.model : "unknown",
            speed: info && info.speed ? info.speed : 0,
            times,
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
    const JNetworkInterface = Java.type("java.net.NetworkInterface");
    const JInet4Address = Java.type("java.net.Inet4Address");
    const JInet6Address = Java.type("java.net.Inet6Address");
    const result = {};
    try {
        const interfaces = JNetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            const iface = interfaces.nextElement();
            let mac = "00:00:00:00:00:00";
            try {
                const hw = iface.getHardwareAddress();
                if (hw !== null) {
                    const parts = [];
                    for (let i = 0; i < hw.length; i++) {
                        const b = hw[i] & 0xff;
                        parts.push(b.toString(16).padStart(2, "0"));
                    }
                    mac = parts.join(":");
                }
            } catch (e) {
                // leave default mac
            }
            let internal = false;
            try {
                internal = Boolean(iface.isLoopback());
            } catch (e) {
                // leave default
            }
            const addrs = iface.getInterfaceAddresses();
            const entries = [];
            for (let i = 0; i < addrs.size(); i++) {
                const ia = addrs.get(i);
                const addr = ia.getAddress();
                const family = addr instanceof JInet4Address ? "IPv4" : (addr instanceof JInet6Address ? "IPv6" : null);
                if (family === null) continue;
                const prefixLen = Number(ia.getNetworkPrefixLength());
                const address = String(addr.getHostAddress()).split("%")[0];
                let netmask;
                if (family === "IPv4") {
                    const bits = prefixLen >= 32 ? 0xffffffff : (~0 << (32 - prefixLen)) >>> 0;
                    netmask = [24, 16, 8, 0].map((shift) => (bits >>> shift) & 0xff).join(".");
                } else {
                    const groups = [];
                    let remaining = prefixLen;
                    for (let g = 0; g < 8; g++) {
                        const bitsInGroup = Math.max(0, Math.min(16, remaining));
                        remaining -= bitsInGroup;
                        const value = bitsInGroup === 0 ? 0 : (0xffff << (16 - bitsInGroup)) & 0xffff;
                        groups.push(value.toString(16));
                    }
                    netmask = groups.join(":");
                }
                entries.push({
                    address,
                    netmask,
                    family,
                    mac,
                    internal,
                    cidr: `${address}/${prefixLen}`,
                    scopeid: family === "IPv6" ? Number(addr.getScopeId ? addr.getScopeId() : 0) : undefined,
                });
            }
            if (entries.length > 0) {
                result[String(iface.getName())] = entries;
            }
        }
    } catch (e) {
        // Interface enumeration can fail under restricted sandboxes/permissions; surface an
        // empty map (matching Node's shape) rather than throwing on environments where this
        // simply isn't available.
        return {};
    }
    return result;
}

export function loadavg() {
    try {
        const JManagementFactory = Java.type("java.lang.management.ManagementFactory");
        const osBean = JManagementFactory.getOperatingSystemMXBean();
        const oneMin = Number(osBean.getSystemLoadAverage());
        if (platform() === "linux") {
            try {
                const JPaths = Java.type("java.nio.file.Paths");
                const JFiles = Java.type("java.nio.file.Files");
                const path = JPaths.get("/proc/loadavg");
                if (JFiles.exists(path)) {
                    const content = String(JFiles.readString(path));
                    const parts = content.trim().split(/\s+/);
                    const one = parseFloat(parts[0]);
                    const five = parseFloat(parts[1]);
                    const fifteen = parseFloat(parts[2]);
                    if (!Number.isNaN(one) && !Number.isNaN(five) && !Number.isNaN(fifteen)) {
                        return [one, five, fifteen];
                    }
                }
            } catch (e) {
                // fall through to the oneMin-only result below
            }
        }
        if (oneMin >= 0) return [oneMin, oneMin, oneMin];
        return [0, 0, 0];
    } catch (e) {
        return [0, 0, 0];
    }
}

export function endianness() {
    const JByteOrder = Java.type("java.nio.ByteOrder");
    return JByteOrder.nativeOrder().toString() === "BIG_ENDIAN" ? "BE" : "LE";
}

export function availableParallelism() {
    return Number(JRuntime.getRuntime().availableProcessors());
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
    availableParallelism,
    constants,
};
