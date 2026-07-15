const { add } = require("./cjs-add.js");
module.exports = { addTwice: (a, b) => add(a, b) * 2 };
