cask "golden-diff@beta" do
  version "1.5.0-beta.9"
  sha256 "3166f5ae85724d58c1b1b58cee3858d1780b0d04de575bab023918e59fc9377d"

  url "https://github.com/dkej123/goldendiff/releases/download/app-beta-v#{version}/Golden-Diff-#{version}.dmg"
  name "Golden Diff Beta"
  desc "Preview releases of the screenshot-test golden image comparison app"
  homepage "https://github.com/dkej123/goldendiff"

  conflicts_with cask: "golden-diff"
  depends_on macos: :big_sur

  app "Golden Diff.app"

  zap trash: "~/.config/golden-diff"

  caveats <<~EOS
    This cask follows the Golden Diff beta channel.

    Golden Diff is currently ad-hoc signed and not notarized. Homebrew verifies the release checksum,
    but Gatekeeper still quarantines the app. If you trust this repository, run after installation:

      xattr -dr com.apple.quarantine "/Applications/Golden Diff.app"

    Why this is needed:
    https://github.com/dkej123/goldendiff/blob/beta/docs/installation.md#install-the-standalone-macos-beta-with-homebrew
  EOS
end
