cask "golden-diff" do
  version "1.4.3"
  sha256 "5edd09e7c884f42073ecbd3e37bc40e1601481caa58b6e256bdac01a0c733217"

  url "https://github.com/dkej123/goldendiff/releases/download/app-v#{version}/Golden-Diff-#{version}.dmg"
  name "Golden Diff"
  desc "Review and compare screenshot-test golden images"
  homepage "https://github.com/dkej123/goldendiff"

  conflicts_with cask: "golden-diff@beta"
  depends_on macos: :big_sur

  app "Golden Diff.app"

  zap trash: "~/.config/golden-diff"

  caveats <<~EOS
    Golden Diff is currently ad-hoc signed and not notarized. Homebrew verifies the release checksum,
    but Gatekeeper still quarantines the app. If you trust this repository, run after installation:

      xattr -dr com.apple.quarantine "/Applications/Golden Diff.app"

    Why this is needed:
    https://github.com/dkej123/goldendiff/blob/beta/docs/installation.md#install-the-homebrew-beta
  EOS
end
