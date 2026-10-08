// T1.6: never return. The target must enforce a wall-clock timeout.
export async function handler() {
  for (;;) { /* spin */ }
}
