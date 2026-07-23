cask "golden-diff@beta" do
  version "1.5.0-beta.1"
  sha256 "dd20ea14957c2f7f8de510fef68411aa24754a41306fd335cd748552963b0f87"

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

    Golden Diff is currently ad-hoc signed and not notarized. If macOS blocks the first launch,
    allow Golden Diff in System Settings > Privacy & Security, then open it again.
  EOS
end
