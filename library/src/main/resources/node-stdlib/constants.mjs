// Deprecated top-level `constants` module (Node's `require('constants')`), served by
// Mycelium as the synthetic core module resolved for `import ... from "constants"` /
// `"node:constants"`. Real Node re-exports this as the union of `os.constants.errno`,
// `os.constants.signals`, and `fs.constants` (plus a handful of OpenSSL/TLS and dlopen
// constants) — this does the same, re-exporting Mycelium's own `fs` constants and filling
// the errno/signal/priority/dlopen tables with the standard Linux/glibc numbering (the
// values real Node itself reports on Linux, which is the platform Mycelium targets).
//
// Every constant is a top-level named export (not just a property of a default-exported
// object) so `import { SIGTERM } from "constants"` and `import * as constants from
// "constants"; Object.keys(constants)` both see the flat surface real Node exposes.

import { constants as fsConstants } from "fs";

export const RTLD_LAZY = 1;
export const RTLD_NOW = 2;
export const RTLD_GLOBAL = 256;
export const RTLD_LOCAL = 0;
export const RTLD_DEEPBIND = 8;

// Standard Linux (glibc) errno numbering.
export const EPERM = 1;
export const ENOENT = 2;
export const ESRCH = 3;
export const EINTR = 4;
export const EIO = 5;
export const ENXIO = 6;
export const E2BIG = 7;
export const ENOEXEC = 8;
export const EBADF = 9;
export const ECHILD = 10;
export const EAGAIN = 11;
export const ENOMEM = 12;
export const EACCES = 13;
export const EFAULT = 14;
export const EBUSY = 16;
export const EEXIST = 17;
export const EXDEV = 18;
export const ENODEV = 19;
export const ENOTDIR = 20;
export const EISDIR = 21;
export const EINVAL = 22;
export const ENFILE = 23;
export const EMFILE = 24;
export const ENOTTY = 25;
export const ETXTBSY = 26;
export const EFBIG = 27;
export const ENOSPC = 28;
export const ESPIPE = 29;
export const EROFS = 30;
export const EMLINK = 31;
export const EPIPE = 32;
export const EDOM = 33;
export const ERANGE = 34;
export const EDEADLK = 35;
export const ENAMETOOLONG = 36;
export const ENOLCK = 37;
export const ENOSYS = 38;
export const ENOTEMPTY = 39;
export const ELOOP = 40;
export const EWOULDBLOCK = 11;
export const ENOMSG = 42;
export const EIDRM = 43;
export const ENOSTR = 60;
export const ENODATA = 61;
export const ETIME = 62;
export const ENOSR = 63;
export const ENOLINK = 67;
export const EPROTO = 71;
export const EMULTIHOP = 72;
export const EBADMSG = 74;
export const EOVERFLOW = 75;
export const EILSEQ = 84;
export const ENOTSOCK = 88;
export const EDESTADDRREQ = 89;
export const EMSGSIZE = 90;
export const EPROTOTYPE = 91;
export const ENOPROTOOPT = 92;
export const EPROTONOSUPPORT = 93;
export const ENOTSUP = 95;
export const EOPNOTSUPP = 95;
export const EAFNOSUPPORT = 97;
export const EADDRINUSE = 98;
export const EADDRNOTAVAIL = 99;
export const ENETDOWN = 100;
export const ENETUNREACH = 101;
export const ENETRESET = 102;
export const ECONNABORTED = 103;
export const ECONNRESET = 104;
export const ENOBUFS = 105;
export const EISCONN = 106;
export const ENOTCONN = 107;
export const ESHUTDOWN = 108;
export const ETOOMANYREFS = 109;
export const ETIMEDOUT = 110;
export const ECONNREFUSED = 111;
export const EHOSTDOWN = 112;
export const EHOSTUNREACH = 113;
export const EALREADY = 114;
export const EINPROGRESS = 115;
export const ESTALE = 116;
export const EDQUOT = 122;
export const ECANCELED = 125;
export const EOWNERDEAD = 130;
export const ENOTRECOVERABLE = 131;

// Standard Linux signal numbering.
export const SIGHUP = 1;
export const SIGINT = 2;
export const SIGQUIT = 3;
export const SIGILL = 4;
export const SIGTRAP = 5;
export const SIGABRT = 6;
export const SIGIOT = 6;
export const SIGBUS = 7;
export const SIGFPE = 8;
export const SIGKILL = 9;
export const SIGUSR1 = 10;
export const SIGSEGV = 11;
export const SIGUSR2 = 12;
export const SIGPIPE = 13;
export const SIGALRM = 14;
export const SIGTERM = 15;
export const SIGSTKFLT = 16;
export const SIGCHLD = 17;
export const SIGCONT = 18;
export const SIGSTOP = 19;
export const SIGTSTP = 20;
export const SIGTTIN = 21;
export const SIGTTOU = 22;
export const SIGURG = 23;
export const SIGXCPU = 24;
export const SIGXFSZ = 25;
export const SIGVTALRM = 26;
export const SIGPROF = 27;
export const SIGWINCH = 28;
export const SIGIO = 29;
export const SIGPOLL = 29;
export const SIGPWR = 30;
export const SIGSYS = 31;

// Scheduling priority constants (matches Node's os.constants.priority / process.setPriority
// range).
export const PRIORITY_LOW = 19;
export const PRIORITY_BELOW_NORMAL = 10;
export const PRIORITY_NORMAL = 0;
export const PRIORITY_ABOVE_NORMAL = -7;
export const PRIORITY_HIGH = -14;
export const PRIORITY_HIGHEST = -20;

// fs.constants (F_OK/R_OK/O_*/COPYFILE_*/etc.) — re-exported flat, same as real Node's
// `constants` module.
export const F_OK = fsConstants.F_OK;
export const R_OK = fsConstants.R_OK;
export const W_OK = fsConstants.W_OK;
export const X_OK = fsConstants.X_OK;
export const O_RDONLY = fsConstants.O_RDONLY;
export const O_WRONLY = fsConstants.O_WRONLY;
export const O_RDWR = fsConstants.O_RDWR;
export const O_CREAT = fsConstants.O_CREAT;
export const O_TRUNC = fsConstants.O_TRUNC;
export const O_APPEND = fsConstants.O_APPEND;
export const COPYFILE_EXCL = fsConstants.COPYFILE_EXCL;

const all = {
    RTLD_LAZY, RTLD_NOW, RTLD_GLOBAL, RTLD_LOCAL, RTLD_DEEPBIND,
    EPERM, ENOENT, ESRCH, EINTR, EIO, ENXIO, E2BIG, ENOEXEC, EBADF, ECHILD, EAGAIN, ENOMEM,
    EACCES, EFAULT, EBUSY, EEXIST, EXDEV, ENODEV, ENOTDIR, EISDIR, EINVAL, ENFILE, EMFILE,
    ENOTTY, ETXTBSY, EFBIG, ENOSPC, ESPIPE, EROFS, EMLINK, EPIPE, EDOM, ERANGE, EDEADLK,
    ENAMETOOLONG, ENOLCK, ENOSYS, ENOTEMPTY, ELOOP, EWOULDBLOCK, ENOMSG, EIDRM, ENOSTR,
    ENODATA, ETIME, ENOSR, ENOLINK, EPROTO, EMULTIHOP, EBADMSG, EOVERFLOW, EILSEQ, ENOTSOCK,
    EDESTADDRREQ, EMSGSIZE, EPROTOTYPE, ENOPROTOOPT, EPROTONOSUPPORT, ENOTSUP, EOPNOTSUPP,
    EAFNOSUPPORT, EADDRINUSE, EADDRNOTAVAIL, ENETDOWN, ENETUNREACH, ENETRESET, ECONNABORTED,
    ECONNRESET, ENOBUFS, EISCONN, ENOTCONN, ESHUTDOWN, ETOOMANYREFS, ETIMEDOUT, ECONNREFUSED,
    EHOSTDOWN, EHOSTUNREACH, EALREADY, EINPROGRESS, ESTALE, EDQUOT, ECANCELED, EOWNERDEAD,
    ENOTRECOVERABLE,
    SIGHUP, SIGINT, SIGQUIT, SIGILL, SIGTRAP, SIGABRT, SIGIOT, SIGBUS, SIGFPE, SIGKILL,
    SIGUSR1, SIGSEGV, SIGUSR2, SIGPIPE, SIGALRM, SIGTERM, SIGSTKFLT, SIGCHLD, SIGCONT,
    SIGSTOP, SIGTSTP, SIGTTIN, SIGTTOU, SIGURG, SIGXCPU, SIGXFSZ, SIGVTALRM, SIGPROF,
    SIGWINCH, SIGIO, SIGPOLL, SIGPWR, SIGSYS,
    PRIORITY_LOW, PRIORITY_BELOW_NORMAL, PRIORITY_NORMAL, PRIORITY_ABOVE_NORMAL,
    PRIORITY_HIGH, PRIORITY_HIGHEST,
    ...fsConstants,
};

export default all;
