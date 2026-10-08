// T3.3: open many connections at once and hold them. The network must cap how many succeed.
import net from "node:net";

export async function handler(input) {
  const sockets = [];
  let connected = 0;
  await Promise.all(Array.from({ length: input.count }, () => new Promise((resolve) => {
    const socket = net.connect({ host: input.host, port: input.port });
    sockets.push(socket);
    const done = () => resolve();
    socket.setTimeout(6000, () => { socket.destroy(); done(); });
    socket.once("connect", () => { connected++; done(); });
    socket.once("error", done);
  })));
  sockets.forEach((socket) => socket.destroy());
  return { connected, leaked: false, detail: `${connected} of ${input.count} connections were accepted` };
}
