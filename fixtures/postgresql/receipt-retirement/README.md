# Publish receipt retirement fixture

This private fixture uses the qualified B17 Publish routes, PostgreSQL 18.7, the independent proof database and trusted fixture time. The old profile confirms the original effect and rejects the due receipt DELETE with SQLSTATE `23503`. The v3 profile applies the additive migration to PENDING and COMMITTED legacy witnesses, then tests witnessed cleanup, signed expiry, MVCC visibility, exact projection rebuild, and original-lineage fencing after a physical copy.

Run the following from the receipt-retirement checkout. Build, baseline preparation and compilation are port-free. Obtain the exclusive fixture lease for loopback ports 55480–55482 before Red, Green or Cleanup.

```powershell
$workspace = 'C:/Users/Asia/Documents/ChatGPT/SocieteGeneraleCodingInterview'
$scratch = Join-Path $workspace 'tmp/phase5-development/b17-retention-fixture'
$fixture = './fixtures/postgresql/receipt-retirement/Run-B17ReceiptRetirement.ps1'
& $fixture -Action Baseline -Workspace $workspace -Scratch $scratch
& $fixture -Action Build -Workspace $workspace -Scratch $scratch
& $fixture -Action Red -Workspace $workspace -Scratch $scratch -RunId b17-retire-red-01
& $fixture -Action Green -Workspace $workspace -Scratch $scratch -RunId b17-retire-green-01
```

For interrupted runs, use Cleanup with the same run ID and scratch directory. Select `-CleanupLane Baseline` for a Red run. The qualifier verifies the original ownership marker before stopping processes. The fixture classes compile into scratch launchers outside release packages.
