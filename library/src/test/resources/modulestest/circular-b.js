exports.loaded = false;
const a = require('./circular-a.js');
exports.aSeenAtLoadTime = a.loaded;
exports.loaded = true;
