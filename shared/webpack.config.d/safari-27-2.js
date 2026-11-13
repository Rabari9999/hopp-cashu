// Safari 27.2's WebAssembly JIT (iOS 27.2, 2026-11): a Wasm function that calls JavaScript which
// passes the call straight on to a Wasm export — a tail call, as an arrow `(...a) => f(...a)` makes
// in a module — hands over a float of 0 or 1 as NaN once the code is warm
// (github.com/eymar/safari_27.2_nan_wasm_f32, JetBrains CMP-10986). Kotlin reaches Skia exactly
// that way, through the wrappers of skiko.mjs: Hopp Web drew a white page, then lists on top of
// each other, then a "+" as a triangle. Safari 27.0 and other browsers are not affected.
//
// Here, while webpack bundles the generated files (a loader, written next to webpack.config.js —
// kept in this file so that Gradle builds anew when it changes):
// - hopp.mjs: Kotlin's module is linked to Skia's Wasm exports directly, Wasm to Wasm, with no
//   JavaScript in between (Skia is loaded first; where linking fails, the old way is taken).
// - skiko.mjs and hopp.import-object.mjs: what still goes through JavaScript is no tail call — a
//   return inside try never is, and the minifier keeps `try { return … } finally {}` as it is
//   (it folds `const r = …; return r` back into the tail call).
// A file that no longer looks as expected (a new Compose or Kotlin) fails the build rather than
// shipping without the fix.
(function (config) {
    const fs = require('fs');
    const path = require('path');
    const loader = path.resolve(__dirname, 'safari-27-2-loader.js');
    fs.writeFileSync(loader, 'module.exports = ' + safari272.toString() + ';\n');
    config.module = config.module || {};
    config.module.rules = config.module.rules || [];
    config.module.rules.push({ test: /[\\/]kotlin[\\/](hopp\.mjs|hopp\.import-object\.mjs|skiko\.mjs)$/, use: [{ loader }] });

    function safari272(source) {
        const file = this.resourcePath.replace(/\\/g, '/');
        const name = file.slice(file.lastIndexOf('/') + 1);
        const fail = (what) => { throw new Error(`safari-27-2-loader: ${name}: ${what} not found — check the fix against this Compose/Kotlin`); };

        if (name === 'hopp.mjs') {
            const call = "wasmInstance = (await WebAssembly.instantiateStreaming(fetch(new URL('./hopp.wasm',import.meta.url).href), importObject, wasmOptions)).instance;";
            if (!source.includes(call)) fail('the browser instantiation');
            return source.replace(call, `wasmInstance = await (async () => {
          const url = new URL('./hopp.wasm',import.meta.url).href;
          let direct = null;
          try {
            const skiko = importObject['./skiko.mjs'];
            await skiko.awaitSkiko;
            const exports = skiko.loadedWasm._, linked = {};
            for (const key of Object.keys(skiko)) linked[key] = typeof exports[key] === 'function' ? exports[key] : skiko[key];
            direct = Object.assign({}, importObject, { './skiko.mjs': linked });
          } catch (e) { console.warn('Hopp: Skia not linked directly', e); }
          if (direct) {
            try { return (await WebAssembly.instantiateStreaming(fetch(url), direct, wasmOptions)).instance; }
            catch (e) { console.warn('Hopp: Skia not linked directly', e); }
          }
          return (await WebAssembly.instantiateStreaming(fetch(url), importObject, wasmOptions)).instance;
        })();`);
        }

        if (name === 'skiko.mjs') {
            let count = 0;
            const out = source.replace(/export let (\w+) = \(\.\.\.a\) => \(\1 = loadedWasm\._\["\1"\]\)\(\.\.\.a\)/g, (_, f) => {
                count++;
                return `export let ${f} = (...a) => { try { return (${f} = loadedWasm._["${f}"])(...a); } finally {} }`;
            });
            if (count < 100) fail(`Skia's wrappers (${count})`);
            return out;
        }

        if (name === 'hopp.import-object.mjs') {
            let count = 0;
            const out = source.replace(/=> wasmExports\['(__callFunction_[^']*)'\]\(([^)]*)\)/g, (_, f, args) => {
                count++;
                return `=> { try { return wasmExports['${f}'](${args}); } finally {} }`;
            });
            if (count === 0) fail("Kotlin's closures");
            return out;
        }
        return source;
    }
})(config);
