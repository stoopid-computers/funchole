export async function handler() {
  console.log("token is", process.env.API_TOKEN);
  console.warn("warn:", process.env.API_TOKEN);
  return { done: true };
}
