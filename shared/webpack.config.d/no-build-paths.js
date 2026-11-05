// Hopp Web names no folder of the machine that built it (review 2026-11-04): webpack writes each
// module's `import.meta.url` into the bundle as its file on disk (Skiko's and SQLite's loaders
// read it), which put the builder's user name and home into the published hopp.js — and made two
// builds of the same commit in different folders differ. Those addresses are only a base the
// loaders never fetch from in a browser; here they all start at file:///hopp/ instead.
(function (config) {
    const path = require('path');
    const { pathToFileURL } = require('url');
    const { Compilation, sources } = require('webpack');
    // This file runs from build/wasm/packages/<module>/: the build's root is two folders up.
    const root = pathToFileURL(path.resolve(__dirname, '../..')).href + '/';
    config.plugins = config.plugins || [];
    config.plugins.push({
        apply(compiler) {
            compiler.hooks.thisCompilation.tap('HoppNoBuildPaths', (compilation) => {
                compilation.hooks.processAssets.tap(
                    { name: 'HoppNoBuildPaths', stage: Compilation.PROCESS_ASSETS_STAGE_SUMMARIZE },
                    (assets) => {
                        for (const name of Object.keys(assets)) {
                            if (!/\.m?js$/.test(name)) continue;
                            const text = assets[name].source().toString();
                            if (text.includes(root)) compilation.updateAsset(name, new sources.RawSource(text.split(root).join('file:///hopp/')));
                        }
                    },
                );
            });
        },
    });
})(config);
