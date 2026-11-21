import { DOMAIN_TEXT, ABUSED_TEXT } from './domainData.js'
import { handle } from './handler.js'

export default {
  async fetch(request, env) {
    const wrapped = new Proxy(env || {}, {
      get(target, prop, receiver) {
        if (prop === 'DOMAIN_TEXT' && !target.DOMAIN_TEXT) return DOMAIN_TEXT
        if (prop === 'ABUSED_TEXT' && !target.ABUSED_TEXT) return ABUSED_TEXT
        return Reflect.get(target, prop, receiver)
      },
    })
    return handle(request, wrapped)
  },
}
