// T1.4/T1.10: can a function open TCP connections to internal or blocked endpoints?
import net from "node:net";

function tryConnect({ host, port }) {
  return new Promise((resolve) => {
    const socket = net.connect({ host, port });
    const done = (ok) => { socket.destroy(); resolve(ok); };
    socket.setTimeout(1500, () => done(false));
    socket.once("connect", () => done(true));
    socket.once("error", () => done(false));
  });
}

export async function handler(input) {
  // All at once: a long list must not run into the caller's own timeout and look "blocked".
  const results = await Promise.all(input.targets.map(async (target) => (await tryConnect(target)) ? `${target.host}:${target.port}` : null));
  const reachable = results.filter(Boolean);
  return { reachable, leaked: reachable.length > 0, detail: reachable.length ? `connected: ${reachable.join(", ")}` : "all blocked" };
}
