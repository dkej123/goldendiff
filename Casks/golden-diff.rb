cask "golden-diff" do
  version "2.0.0"
  sha256 "e6ffbd00e25d9771fa491f5e98db636597ca67c6218bb1fd54b2ce2f079d0e0e"

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
    https://github.com/dkej123/goldendiff/blob/main/docs/installation.md#install-the-standalone-macos-app-with-homebrew
  EOS
end
