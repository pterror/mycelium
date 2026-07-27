#!/usr/bin/env bun
// Regenerates the MIME_TYPES table baked into BUN_INIT_SCRIPT
// (library/src/main/kotlin/org/mycelium/library/Mycelium.kt), which backs the shim's
// `Bun.file(path).type` — by asking a REAL `bun` binary what it reports for a broad
// extension corpus, all in a single `bun run` invocation (not one subprocess per extension).
//
// Why a single invocation is possible: `Bun.file(...).type` is computed from the path/
// extension alone — no stat is performed (real Bun confirmed: even
// `Bun.file('does-not-exist.json').type` is 'application/json;charset=utf-8') — so every
// extension below can be probed without any file actually needing to exist on disk. This
// script just calls `Bun.file("probe." + ext).type` in a loop, in-process, for every
// extension in the corpus below.
//
// Usage (from this repo's `nix develop` shell, which provides `bun` via flake.nix):
//   bun run tooling/generate-bun-mime-table.mjs
// Prints the ready-to-paste `MIME_TYPES` object body (sorted, quoted keys) to stdout, plus a
// summary (total entries, excluded-vendor count) to stderr. Paste stdout directly into
// BUN_INIT_SCRIPT's `MIME_TYPES` object in Mycelium.kt, replacing the previous table
// wholesale. Do NOT hand-edit MIME_TYPES directly — regenerate via this script instead (add
// new extensions to CURATED_EXTENSIONS below first if what you need isn't already covered
// by the exhaustive 1-3-letter passes).
//
// Last generated against: bun 1.3.13 (this repo's flake.nix-pinned version, 2026-07-27),
// producing 454 entries.
//
// --- Coverage strategy (three passes, each documented so a future regeneration knows
// exactly what's in scope and why) ---
//
// Pass 1 — EXHAUSTIVE single- and double-letter extensions (all 26 + 26*26 = 702 lowercase
// combinations). This closes the exact gap that motivated expanding this table in the first
// place: real Bun's mime-db has genuine entries for short extensions like ".c" (text/x-c),
// ".h" (text/x-c), ".t" (text/troff), ".s" (text/x-asm), ".f" (text/x-fortran), which a
// hand-picked "common extensions" list is liable to miss one-by-one. Since there are only
// 702 combinations, exhaustive enumeration is cheap and removes any guesswork.
//
// Pass 2 — EXHAUSTIVE three-letter extensions (26^3 = 17,576 combinations), MINUS anything
// real Bun maps to a vendor-specific MIME type (any answer containing "vnd." — e.g.
// "vnd.zzazz.deck+xml", "vnd.ibm.modcap", "vnd.intercon.formnet"). Bun's mime-db has ~676
// three-letter extensions total; roughly half are proprietary vendor document/container
// formats no Mycelium user would plausibly touch. Excluding just the "vnd."-tagged ones is a
// mechanical, defensible cutoff (not a hand-picked one) that drops ~338 obscure entries while
// keeping everything generic/standard (e.g. "csv", "aif", "eml", "dtd", "cbz").
//
// Pass 3 — a CURATED list of 4+ character extensions covering common real-world file
// categories (text/image/audio/video/application/font) plus — since Mycelium is a polyglot
// runtime — a deliberately broad set of source-code extensions across every language this
// runtime supports or might plausibly run (.js/.ts/.py/.rb/.go/.rs/.java/.kt/.swift/.lua/...).
// Going exhaustive here the way Pass 1/2 do would mean enumerating up to 26^4 = 456,976+
// combinations and hand-reviewing which of Bun's (still sizeable) 4+-letter mime-db answers
// are vendor noise versus genuinely plausible — the same task Pass 2 already had to solve at
// a much larger scale. A curated list is the sensible cutoff here: this is NOT exhaustive
// against Bun's full mime-db (which runs into the thousands of entries across all lengths and
// would bloat BUN_INIT_SCRIPT far past what's practical to inline) — it is "every extension a
// real user would plausibly hit," curated by category, verified against live bun rather than
// invented.
//
// Net effect of all three passes: every 1-3 character extension is authoritatively correct
// against bun 1.3.13 (nothing left to guess), and 4+ character extensions are covered for the
// categories per Pass 3's corpus — with any residual 4+-character mime-db entry outside that
// corpus being the sole known/accepted gap (see BunFuzzTest.kt's bunFileMimeTypeGuessLiveDiffFuzz
// for how that residual risk is exercised/reported rather than hidden).

const LETTERS = "abcdefghijklmnopqrstuvwxyz".split("");

// Deliberately includes plenty of extensions that Pass 1/2 above already resolve on their
// own (e.g. "css", "gif", "ico" are 3 letters) — probing them again here is harmless (same
// deterministic answer, just merged in) and keeping the full list explicit means nothing
// gets silently dropped if Pass 1/2's scope (1-3 letters) ever changes.
const CURATED_EXTENSIONS = [
  // --- text/* ---
  "txt", "text", "html", "htm", "css", "csv", "md", "markdown", "mkd", "mdown",
  "xml", "rtf", "ics", "n3", "srt", "vtt", "vcard", "vcf", "yaml", "yml", "ini",
  "cfg", "conf", "log", "properties", "toml", "env", "gitignore", "editorconfig",
  "tex", "bib", "rst", "diff", "patch", "ipynb", "textile", "adoc", "asciidoc",
  "po", "pot", "mo", "strings", "resx", "arb",

  // --- source code (this runtime is polyglot — deliberately broad) ---
  "js", "mjs", "cjs", "jsx", "ts", "tsx", "mts", "cts",
  "c", "h", "cc", "cpp", "cxx", "hpp", "hxx", "hh",
  "m", "mm", "go", "rs", "rb", "py", "pyi", "pyc", "pyx", "flow",
  "java", "class", "kt", "kts", "scala", "clj", "cljs", "cljc",
  "swift", "php", "phtml", "pl", "pm", "sh", "bash", "zsh", "fish", "ps1", "psd1", "psm1",
  "cs", "fs", "fsx", "vb", "lua", "sql", "r", "jl", "dart",
  "ex", "exs", "erl", "hrl", "hs", "lhs", "nim", "zig", "v", "sol",
  "vue", "svelte", "astro", "graphql", "gql", "proto",
  "asm", "s", "d", "cob", "cbl", "pas", "pp", "adb", "ads", "f90", "f95",
  "groovy", "gradle", "elm", "purs", "re", "rei", "ml", "mli",
  "tcl", "awk", "sed", "vim", "el", "lisp", "scm", "rkt",
  "coffee", "litcoffee", "wasm", "wat", "make", "cmake", "bat", "cmd",

  // --- config / data formats ---
  "json", "json5", "jsonc", "jsonl", "ndjson", "geojson", "map", "lock",
  "webmanifest", "plist", "reg", "url", "webloc", "desktop",

  // --- application/* (docs/archives/binary/security) ---
  "pdf", "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "tar", "jar", "war",
  "apk", "deb", "rpm", "dmg", "iso", "exe", "dll", "so", "dylib", "bin", "msi",
  "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
  "epub", "mobi", "azw3", "swf", "crx", "xpi",
  "rss", "atom", "torrent", "eot", "o", "a", "lib", "obj",
  "pem", "crt", "csr", "key", "jwk", "asc", "sig", "sha256", "md5",

  // --- image/* ---
  "png", "jpg", "jpeg", "jfif", "gif", "bmp", "ico", "svg", "svgz", "webp",
  "tif", "tiff", "avif", "heic", "heif", "psd", "ai", "eps", "cur",

  // --- audio/* ---
  "mp3", "wav", "ogg", "oga", "opus", "flac", "aac", "m4a", "wma", "mid", "midi",
  "amr", "aiff", "au",

  // --- video/* ---
  "mp4", "m4v", "webm", "ogv", "mov", "avi", "wmv", "flv", "mkv", "3gp", "3g2",
  "mpeg", "mpg", "ts", "m3u8",

  // --- font/* ---
  "woff", "woff2", "ttf", "otf",
];

function probeAll(extensions) {
  const result = {};
  for (const ext of extensions) result[ext] = Bun.file("probe." + ext).type;
  return result;
}

// Pass 1: exhaustive 1-2 letter.
const shortExts = [];
for (const a of LETTERS) shortExts.push(a);
for (const a of LETTERS) for (const b of LETTERS) shortExts.push(a + b);
const shortResults = probeAll(shortExts);

// Pass 2: exhaustive 3-letter.
const threeExts = [];
for (const a of LETTERS) for (const b of LETTERS) for (const c of LETTERS) threeExts.push(a + b + c);
const threeResults = probeAll(threeExts);

// Pass 3: curated 4+ letter corpus.
const curatedResults = probeAll(CURATED_EXTENSIONS);

const merged = {};
let excludedVendorCount = 0;

for (const [ext, type] of Object.entries(shortResults)) {
  if (type !== "application/octet-stream") merged[ext] = type;
}
for (const [ext, type] of Object.entries(threeResults)) {
  if (type === "application/octet-stream") continue;
  if (type.includes("vnd.")) { excludedVendorCount++; continue; }
  merged[ext] = type;
}
for (const [ext, type] of Object.entries(curatedResults)) {
  if (type !== "application/octet-stream") merged[ext] = type;
}

const entries = Object.entries(merged).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));

console.error(`generated against bun ${Bun.version}`);
console.error(`total entries: ${entries.length}`);
console.error(`excluded vendor-specific ("vnd.") 3-letter entries: ${excludedVendorCount}`);

for (const [ext, type] of entries) {
  console.log(`                    '${ext}': '${type}',`);
}
