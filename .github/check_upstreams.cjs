const fs = require('node:fs');

module.exports = async ({github, context, core}) => {
  const pins = Object.fromEntries(fs.readFileSync('streambridge.version.properties', 'utf8')
    .split(/\r?\n/).map(line => line.trim()).filter(line => line && !line.startsWith('#'))
    .map(line => { const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)]; }));
  const sources = [
    {owner: 'NuvioMedia', repo: 'NuvioMobile', commit: pins.NUVIO_UPSTREAM_COMMIT, tag: pins.NUVIO_UPSTREAM_RELEASE},
    {owner: 'luqmanfadlli', repo: 'NuvioMobile-Enhanced', commit: pins.NUVIO_ENHANCED_COMMIT, tag: pins.NUVIO_ENHANCED_RELEASE},
  ];
  const changes = [];
  for (const source of sources) {
    const {owner, repo} = source;
    const {data} = await github.rest.repos.listReleases({owner, repo, per_page: 20});
    const release = data.filter(item => !item.draft)
      .sort((a, b) => Date.parse(b.published_at) - Date.parse(a.published_at))[0];
    if (!release) throw new Error(`No published release found for ${owner}/${repo}`);
    let object = (await github.rest.git.getRef({owner, repo, ref: `tags/${release.tag_name}`})).data.object;
    for (let depth = 0; object.type === 'tag' && depth < 5; depth++) {
      object = (await github.rest.git.getTag({owner, repo, tag_sha: object.sha})).data.object;
    }
    if (object.type !== 'commit' || !/^[0-9a-f]{40}$/.test(object.sha)) throw new Error('Release tag did not resolve to a commit');
    core.info(`${owner}/${repo}: published ${release.tag_name}, ${object.sha}`);
    if (release.tag_name !== source.tag || object.sha !== source.commit) changes.push({source, release, commit: object.sha});
  }
  if (!changes.length) return;
  const title = '[StreamBridge upstream audit] New official/Enhanced published release';
  const lines = ['A published upstream release differs from the canonical StreamBridge source pins.', '',
    'This is a review notification only: no moving branch is substituted and no code is auto-merged.', '',
    ...changes.flatMap(({source, release, commit}) => [
      `## ${source.owner}/${source.repo}`,
      `Pinned: ${source.tag} at ${source.commit}`,
      `Published candidate: ${release.tag_name.replace(/[`\r\n]/g, '')} at ${commit}`,
      `Release: ${release.html_url}`,
      `Published UTC: ${release.published_at}`, '',
    ]),
    'Follow docs/UPSTREAM-SYNC.md: verify both tags independently, preserve StreamBridge overlays,',
    'keep the CloudStream experiment excluded, audit Android+iOS and dependencies/licenses, then',
    'actually compile/test/archive before updating canonical metadata or proposing a release.'];
  const {data: issues} = await github.rest.issues.listForRepo({owner: context.repo.owner, repo: context.repo.repo, state: 'open', per_page: 100});
  const existing = issues.find(issue => !issue.pull_request && issue.title === title);
  const request = {owner: context.repo.owner, repo: context.repo.repo, body: lines.join('\n')};
  if (existing) await github.rest.issues.update({...request, issue_number: existing.number});
  else await github.rest.issues.create({...request, title});
};
