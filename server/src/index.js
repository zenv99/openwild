import { handle } from './handler.js'

export default {
  async fetch(request, env) {
    return handle(request, env)
  },
}
