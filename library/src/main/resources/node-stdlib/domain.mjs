// Deprecated `domain` module, served by Mycelium as the synthetic core module resolved for
// `import ... from "domain"` / `"node:domain"`. Node itself has deprecated this in favor of
// async_hooks/AsyncLocalStorage; this is a functional-shape stub — `run`/`bind`/`intercept`
// genuinely invoke the wrapped function and route thrown/emitted errors to the domain's
// 'error' listeners, but there's no real implicit context propagation across the event loop
// the way native Node's domain module has (it hooks every async resource under the hood;
// Mycelium doesn't reproduce that machinery here).

import { EventEmitter } from "events";

let activeDomain = null;

export class Domain extends EventEmitter {
    constructor() {
        super();
        this.members = [];
    }

    add(emitter) {
        if (this.members.indexOf(emitter) !== -1) return;
        this.members.push(emitter);
        if (emitter && typeof emitter === "object") {
            emitter.domain = this;
        }
    }

    remove(emitter) {
        const idx = this.members.indexOf(emitter);
        if (idx !== -1) this.members.splice(idx, 1);
        if (emitter && typeof emitter === "object" && emitter.domain === this) {
            emitter.domain = null;
        }
    }

    bind(fn) {
        const domain = this;
        function bound(...args) {
            const previous = activeDomain;
            activeDomain = domain;
            try {
                return fn.apply(this, args);
            } catch (err) {
                domain.emit("error", err);
            } finally {
                activeDomain = previous;
            }
        }
        bound.domain = domain;
        return bound;
    }

    intercept(fn) {
        const domain = this;
        function intercepted(err, ...args) {
            if (err) {
                domain.emit("error", err);
                return;
            }
            const previous = activeDomain;
            activeDomain = domain;
            try {
                return fn.apply(this, args);
            } catch (e) {
                domain.emit("error", e);
            } finally {
                activeDomain = previous;
            }
        }
        intercepted.domain = domain;
        return intercepted;
    }

    run(fn, ...args) {
        const previous = activeDomain;
        activeDomain = this;
        this.enter();
        try {
            return fn.apply(this, args);
        } catch (err) {
            this.emit("error", err);
        } finally {
            this.exit();
            activeDomain = previous;
        }
    }

    enter() {
        activeDomain = this;
    }

    exit() {
        if (activeDomain === this) activeDomain = null;
    }

    dispose() {
        this.members.slice().forEach((m) => this.remove(m));
        this.removeAllListeners();
    }
}

export function create() {
    return new Domain();
}

export const createDomain = create;

export const active = null;

export default {
    Domain,
    create,
    createDomain,
    active,
};
