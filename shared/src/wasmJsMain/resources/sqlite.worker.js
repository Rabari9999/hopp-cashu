// The web client (ADR 0022): SQLDelight's web-worker protocol on the official SQLite
// Wasm build, the database in the origin private file system (OPFS). The
// "opfs-sahpool" VFS needs no COOP/COEP headers; without OPFS (an old browser,
// some private modes) the database lives in memory and says so. So it does from the
// start when named "hopp-tab-only" ("This is not my device", ADR 0022, 2), and after
// "PRAGMA hopp_tab_only": the database copied into memory, its files deleted.
//
// Protocol (app.cash.sqldelight.driver.worker): {id, action, sql, params} in,
// {id, results: {values: [[...]]}} or {id, error} out. For a statement without
// result columns the driver reads the changed-row count from values[0][0].
import sqlite3InitModule from "@sqlite.org/sqlite-wasm";

let sqlite3 = null;
let pool = null;
let db = null;
let storage = "memory";

const ready = (async () => {
  sqlite3 = await sqlite3InitModule();
  if (self.name === "hopp-tab-only") {
    db = new sqlite3.oo1.DB(":memory:", "c");
    return;
  }
  // One worker holds the pool's file handles; a worker just terminated (a
  // reload, another tab closing) may still hold them for a moment.
  for (let attempt = 0; attempt < 10 && !db; attempt++) {
    try {
      pool = await sqlite3.installOpfsSAHPoolVfs({ name: "hopp" });
      db = new pool.OpfsSAHPoolDb("/hopp.db");
      storage = "opfs-sahpool";
    } catch (e) {
      await new Promise((r) => setTimeout(r, 100));
    }
  }
  if (!db) db = new sqlite3.oo1.DB(":memory:", "c");
})();

// The database from here on in memory only: a copy of it, then the file and its pool deleted.
// The swap is synchronous, so no statement runs between; the deletion may finish later.
function tabOnly() {
  if (storage === "memory") return { values: [[0]] };
  const bytes = sqlite3.capi.sqlite3_js_db_export(db.pointer);
  const copy = new sqlite3.oo1.DB(":memory:", "c");
  const p = sqlite3.wasm.allocFromTypedArray(bytes);
  // SQLITE_DESERIALIZE_FREEONCLOSE | SQLITE_DESERIALIZE_RESIZEABLE: SQLite owns the copy's memory.
  const rc = sqlite3.capi.sqlite3_deserialize(copy.pointer, "main", p, bytes.byteLength, bytes.byteLength, 1 | 2);
  if (rc) { copy.close(); throw new Error(`deserialize failed: ${rc}`); }
  db.close();
  db = copy;
  storage = "memory";
  return pool.removeVfs().then(() => ({ values: [[1]] }));
}

function exec(sql, params) {
  if (sql === "PRAGMA hopp_storage") return { values: [[storage]] };
  if (sql === "PRAGMA hopp_tab_only") return tabOnly();
  const rows = [];
  const columns = [];
  db.exec({ sql, bind: params && params.length ? params : undefined, rowMode: "array", resultRows: rows, columnNames: columns });
  return { values: columns.length ? rows : [[db.changes()]] };
}

self.onmessage = async (event) => {
  const data = event.data;
  try {
    await ready;
    let results;
    switch (data && data.action) {
      case "exec": results = await exec(data.sql, data.params); break;
      case "begin_transaction": results = exec("BEGIN TRANSACTION;"); break;
      case "end_transaction": results = exec("END TRANSACTION;"); break;
      case "rollback_transaction": results = exec("ROLLBACK TRANSACTION;"); break;
      default: throw new Error(`Unsupported action: ${data && data.action}`);
    }
    postMessage({ id: data.id, results });
  } catch (err) {
    postMessage({ id: data && data.id, error: { message: String(err && err.message || err), name: err && err.name } });
  }
};
