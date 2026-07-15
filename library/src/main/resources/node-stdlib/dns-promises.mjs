// Minimal Node.js-compatible `dns/promises` module, served by Mycelium as the synthetic
// core module resolved for `import ... from "dns/promises"` / `"node:dns/promises"`.
// Re-exports the promise-based namespace dns.mjs already builds (see its `promises`
// export) as this module's own top-level bindings, mirroring Node's own dns/promises
// module (which is really just dns's `promises` property, importable standalone).

import { promises } from "dns";

export const {
    lookup,
    lookupService,
    resolve,
    resolve4,
    resolve6,
    reverse,
    resolveMx,
    resolveTxt,
    resolveSrv,
    resolveNs,
    resolveCname,
    resolveSoa,
    resolvePtr,
    resolveNaptr,
    resolveCaa,
    resolveAny,
    getServers,
    setServers,
    Resolver,
} = promises;

export { ADDRCONFIG, V4MAPPED, ALL } from "dns";

export default promises;
