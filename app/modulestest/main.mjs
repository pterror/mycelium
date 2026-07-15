import "./import.mjs";
import "http://curls.it/hkibj.mjs";
import "https://curls.it/4JVnm.mjs";
import { i } from "./constant.mjs";
import "./import.rb";
import { add, hello } from "./hello.c";
console.log(i);
console.log("add(2, 3) =", add(2, 3));
hello();
Polyglot.eval("js", "console.log('hello from js polyglot eval!')")
Polyglot.eval("python", "print('hello from python polyglot eval!')")
Polyglot.eval("ruby", "print \"hello from ruby polyglot eval!\\n\"")
Polyglot.eval("ruby", "require_relative 'app/modulestest/import'")
// FIXME:
// Polyglot.eval("python", "from .modulestest.import_ import *")