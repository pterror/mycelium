exports.loaded = false;
const b = require('./circular-b.js');
exports.bSeenAtLoadTime = b.loaded;
exports.loaded = true;
