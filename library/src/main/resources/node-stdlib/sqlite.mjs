// Minimal `node:sqlite` module stub, served by Mycelium as the synthetic core module
// resolved for `import ... from "node:sqlite"` (`node:`-only, same as `node:test`/
// `node:sea`). Real Node bundles its own native SQLite; Mycelium has no SQLite driver wired
// in (no JDBC dependency ships with the library today), so this only provides the
// `DatabaseSync`/`StatementSync`/`Session` API shape — every operation that would need an
// actual database throws, matching the experience of calling this API when the underlying
// native binding is unavailable.

function notSupported(what) {
    throw new Error(`mycelium: node:sqlite ${what} is not supported (no SQLite backend is wired into Mycelium)`);
}

export class StatementSync {
    constructor() {
        notSupported("StatementSync");
    }
    run() {
        notSupported("StatementSync#run");
    }
    get() {
        notSupported("StatementSync#get");
    }
    all() {
        notSupported("StatementSync#all");
    }
    iterate() {
        notSupported("StatementSync#iterate");
    }
    columns() {
        notSupported("StatementSync#columns");
    }
    setAllowBareNamedParameters() {
        notSupported("StatementSync#setAllowBareNamedParameters");
    }
    setAllowUnknownNamedParameters() {
        notSupported("StatementSync#setAllowUnknownNamedParameters");
    }
    setReadBigInts() {
        notSupported("StatementSync#setReadBigInts");
    }
    sourceSQL() {
        notSupported("StatementSync#sourceSQL");
    }
    expandedSQL() {
        notSupported("StatementSync#expandedSQL");
    }
}

export class Session {
    constructor() {
        notSupported("Session");
    }
    changeset() {
        notSupported("Session#changeset");
    }
    patchset() {
        notSupported("Session#patchset");
    }
    close() {
        notSupported("Session#close");
    }
}

export class DatabaseSync {
    constructor(location, options) {
        this.location = location;
        this._open = false;
    }

    open() {
        notSupported("DatabaseSync#open");
    }

    close() {
        this._open = false;
    }

    prepare(sql) {
        notSupported("DatabaseSync#prepare");
    }

    exec(sql) {
        notSupported("DatabaseSync#exec");
    }

    function(name, options, fn) {
        notSupported("DatabaseSync#function");
    }

    aggregate(name, options) {
        notSupported("DatabaseSync#aggregate");
    }

    createTagStore() {
        notSupported("DatabaseSync#createTagStore");
    }

    createSession(options) {
        notSupported("DatabaseSync#createSession");
    }

    applyChangeset(changeset, options) {
        notSupported("DatabaseSync#applyChangeset");
    }

    enableLoadExtension(enable) {
        // no-op
    }

    enableDefensive(enable) {
        // no-op
    }

    loadExtension(path) {
        notSupported("DatabaseSync#loadExtension");
    }

    serialize(name) {
        notSupported("DatabaseSync#serialize");
    }

    deserialize(buffer) {
        notSupported("DatabaseSync#deserialize");
    }

    setAuthorizer(authorizer) {
        notSupported("DatabaseSync#setAuthorizer");
    }

    get isOpen() {
        return this._open;
    }
}

export const constants = {
    SQLITE_CHANGESET_OMIT: 0,
    SQLITE_CHANGESET_REPLACE: 1,
    SQLITE_CHANGESET_ABORT: 2,
    SQLITE_CHANGESET_DATA: 1,
    SQLITE_CHANGESET_NOTFOUND: 2,
    SQLITE_CHANGESET_CONFLICT: 3,
    SQLITE_CHANGESET_CONSTRAINT: 4,
    SQLITE_CHANGESET_FOREIGN_KEY: 5,
};

export function backup(sourceDb, destinationPath, options) {
    return Promise.reject(new Error("mycelium: node:sqlite.backup() is not supported"));
}

export default {
    DatabaseSync,
    StatementSync,
    Session,
    constants,
    backup,
};
