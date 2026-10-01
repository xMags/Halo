import { createEgressProxy, loadEgressProxyEnv } from './egressProxy'

// Entry point for the egress proxy container: same image as the API, its own
// command (`node --import tsx apps/api/src/egressMain.ts`). Configuration comes
// from the environment only; it has no .env file and no database.
//   EGRESS_ALLOWED_CLIENTS  peers that may open tunnels, by IP (required)
//   EGRESS_ALLOWED_HOSTS    hostnames tunnels may reach (required)
//   EGRESS_HOST, EGRESS_PORT  where to listen (default 0.0.0.0:8790); publish
//                           it on the private network's address only
const env = loadEgressProxyEnv()

const server = createEgressProxy({
  allowedClients: env.allowedClients,
  allowedHosts: env.allowedHosts,
  log: (line) => console.log(line),
})

server.listen(env.port, env.host, () => {
  console.log(
    `halo egress proxy on ${env.host}:${env.port}; hosts ${[...env.allowedHosts].join(', ')}; clients ${[...env.allowedClients].join(', ')}`,
  )
})

// As PID 1 in a container, node would otherwise ignore the stop signal and
// wait out Docker's grace period.
for (const signal of ['SIGTERM', 'SIGINT'] as const) {
  process.on(signal, () => process.exit(0))
}
