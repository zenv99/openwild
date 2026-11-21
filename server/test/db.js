import { DatabaseSync } from 'node:sqlite'

export function memoryDb(schema) {
  const db = new DatabaseSync(':memory:')
  db.exec(schema)
  return {
    prepare(sql) {
      return {
        bind(...args) {
          const stmt = db.prepare(sql)
          const bound = args.map((v) => (v === undefined ? null : v))
          return {
            async run() {
              stmt.run(...bound)
              return { success: true }
            },
            async first() {
              return stmt.get(...bound) ?? null
            },
            async all() {
              return { results: stmt.all(...bound) }
            },
          }
        },
      }
    },
  }
}

export function memoryR2() {
  const map = new Map()
  return {
    async put(key, value) {
      map.set(key, value)
    },
    async get(key) {
      const v = map.get(key)
      if (!v) return null
      return { arrayBuffer: async () => v }
    },
    async delete(key) {
      map.delete(key)
    },
  }
}
