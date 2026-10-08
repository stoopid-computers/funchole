import { redirect } from "next/navigation";

// Account settings live in the Settings hub.
export default function AccountPage() {
  redirect("/settings");
}
