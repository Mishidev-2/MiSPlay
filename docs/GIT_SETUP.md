# Git setup and first commit

The IntelliJ project was created with **Create Git repository** checked. This guide assumes that existing local repository; it does not create another project directory.

## Install these documentation files

Copy `README.md`, `CHANGELOG.md` and the `docs` folder into the project root, alongside `pom.xml`. Add `.gitattributes` too. Merge the supplied `.gitignore` rules into the existing `.gitignore`, preserving any useful local rules. If `.gitattributes` already exists, merge those rules as well.

Keep application source files and `pom.xml` from the working project. This documentation package contains no replacement Java source or Maven configuration.

## Check the local repository

Open PowerShell or IntelliJ's terminal in the project root:

```powershell
git rev-parse --show-toplevel
git status
git config --get user.name
git config --get user.email
```

The first command should point to your MiSPlay folder. If the name/email are missing or incorrect, set your chosen commit identity for this repository. Replace both example values before running:

```powershell
git config user.name "YOUR NAME"
git config user.email "YOUR COMMIT EMAIL"
```

These commands do not change the identity used by other repositories.

## Review and make the first commit

Complete the applicable playback checks in [DEVELOPMENT.md](DEVELOPMENT.md#manual-checks). Then stage the project and review the staged files:

```powershell
git add .
git diff --cached --stat
git diff --cached --check
git status --short
```

Expected tracked content includes Java source, `pom.xml`, documentation, `.gitignore`, `.gitattributes` and any intentional Maven configuration under `.mvn`. A Maven Wrapper is not part of this checkpoint.

Build output under `target`, local IntelliJ configuration under `.idea`, personal audio, logs and profiling output should not be staged. Ignore rules do not remove files already in the index. If `.idea` or `target` was previously staged, remove only those entries from Git's index, keeping the files on disk:

```powershell
git rm -r --cached --ignore-unmatch .idea target
git status --short
```

When the staged change is correct:

```powershell
git commit -m "feat: initialize MiSPlay with local MP3 playback"
```

Check the branch name:

```powershell
git branch --show-current
```

If it is still `master`, rename it to `main`:

```powershell
git branch -m main
```

## Add GitHub hosting

Create an empty repository named `MiSPlay` on [GitHub](https://github.com/new). Start with **Private** if that suits your personal project. Leave automatic README, ignore-file and license initialization off because the local repository already contains its initial files.

Copy the new repository's HTTPS URL. Check existing remotes, then add it as `origin` if no origin is configured:

```powershell
git remote -v
git remote add origin "https://github.com/YOUR_USERNAME/MiSPlay.git"
git push -u origin main
```

Replace `YOUR_USERNAME` with the repository owner's actual GitHub username. Complete Git's authentication prompt if shown. If `origin` already exists, inspect it and verify the intended repository before changing its URL.

Confirm the upload by opening the repository page and checking that the README and source are present. Locally:

```powershell
git status
git log -1 --oneline
```

## Later commits

After a working checkpoint, review the changes, update the relevant documentation, stage them, inspect the staged summary and commit with the proposed message. With the upstream configured, `git push` uploads subsequent commits.

## References

- [GitHub: add an existing local repository](https://docs.github.com/en/migrations/importing-source-code/using-the-command-line-to-import-source-code/adding-locally-hosted-code-to-github)
- [Git ignore rules](https://git-scm.com/docs/gitignore)
- [Git text attributes](https://git-scm.com/docs/gitattributes)
