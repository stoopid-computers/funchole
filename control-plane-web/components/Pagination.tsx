import { Button } from "@/components/Button";

interface PaginationProps {
  page: number;
  totalPages: number;
  totalElements: number;
  onChange: (page: number) => void;
  disabled?: boolean;
}

export function Pagination({
  page,
  totalPages,
  totalElements,
  onChange,
  disabled = false,
}: PaginationProps) {
  if (totalElements === 0) {
    return null;
  }
  return (
    <div className="flex items-center justify-between gap-3 pt-1 text-sm text-muted-foreground">
      <span className="font-mono text-xs">
        {totalElements} total &middot; page {page} of {totalPages}
      </span>
      <div className="flex gap-2">
        <Button size="sm" disabled={page <= 1 || disabled} onClick={() => onChange(page - 1)}>
          Previous
        </Button>
        <Button size="sm" disabled={page >= totalPages || disabled} onClick={() => onChange(page + 1)}>
          Next
        </Button>
      </div>
    </div>
  );
}
