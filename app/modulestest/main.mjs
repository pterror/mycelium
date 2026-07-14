// FIXME: imports are being resolved from the wrong location
import "./app/modulestest/import.mjs";
import "http://curls.it/hkibj.mjs";
import "https://curls.it/4JVnm.mjs";
import { i } from "./app/modulestest/constant.mjs";
import "./app/modulestest/import.rb";
import { add, hello } from "./app/modulestest/hello.c";
console.log(i);
console.log("add(2, 3) =", add(2, 3));
hello();
Polyglot.eval("js", "console.log('hello from js polyglot eval!')")
Polyglot.eval("python", "print('hello from python polyglot eval!')")
Polyglot.eval("ruby", "print \"hello from ruby polyglot eval!\\n\"")
Polyglot.eval("ruby", "require_relative 'app/modulestest/import'")
// FIXME:
// Polyglot.eval("python", "from .modulestest.import_ import *")