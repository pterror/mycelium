// FIXME: imports are being resolved from the wrong location
import "./modulestest/import.mjs";
import "http://curls.it/hkibj.mjs";
import "https://curls.it/4JVnm.mjs";
import { i } from "./modulestest/constant.mjs";
console.log(i);
Polyglot.eval("js", "console.log('hello from js polyglot eval!')")
Polyglot.eval("python", "print('hello from python polyglot eval!')")
Polyglot.eval("ruby", "print \"hello from ruby polyglot eval!\\n\"")
Polyglot.eval("ruby", "require_relative 'modulestest/import'")
// FIXME:
// Polyglot.eval("python", "from .modulestest.import_ import *")