export async function handler() {
  console.log("plain", { a: 1 });
  console.info("info line");
  console.error("error line");
  return { done: true };
}
