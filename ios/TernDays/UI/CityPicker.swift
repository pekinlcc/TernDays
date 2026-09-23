import SwiftUI

/// 选城市:搜索框 + 结果 / 建议列表,补记与更正共用(此前两处各写一份,行为不一致)。
///
///  - 输入去掉首尾空白后 150ms 防抖再搜,全表扫描在后台线程;
///  - 每次搜索带代次号,旧结果回来时代次已变就丢掉(快速输入不会被慢结果覆盖);
///  - 搜索中显示转圈,没结果时给出换个写法的提示;
///  - 没输入时列出建议城市(前一天 / 后一天的城市在前,再是最近去过的)。
///
/// 用在 List 里:本视图输出若干行,由调用方放进 Section。
struct CityPicker: View {
    let suggestions: [CityOption]
    let onPick: (String, String) -> Void

    @State private var query = ""
    @State private var hits: [CityMatcher.SearchHit] = []
    @State private var loading = false
    @State private var generation = 0

    private var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        TextField("搜索城市名（支持拼音）", text: $query)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled(true)
            .task(id: query) { await search() }
        if trimmed.isEmpty {
            ForEach(suggestions) { c in
                cityRow(name: c.name, note: c.note) { onPick(c.key, c.name) }
            }
        } else if loading {
            HStack(spacing: 8) {
                ProgressView()
                Text("正在搜索…").font(.system(size: 13)).foregroundColor(Td.faint)
            }
        } else if hits.isEmpty {
            Text("没有匹配的城市,可换拼音或英文名试试")
                .font(.system(size: 13)).foregroundColor(Td.muted)
        } else {
            ForEach(hits, id: \.cityKey) { hit in
                cityRow(name: hit.cityName, note: hit.region) { onPick(hit.cityKey, hit.cityName) }
            }
        }
    }

    private func cityRow(name: String, note: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(name).foregroundColor(Td.ink)
                if !note.isEmpty {
                    Text(note).font(.system(size: 12)).foregroundColor(Td.faint)
                }
                Spacer()
            }
            .tapTarget()
        }
    }

    /// query 变化时 SwiftUI 会取消上一次的 task;代次号再兜一层:
    /// 后台搜索本身不可取消,回来时代次对不上就丢弃结果。
    private func search() async {
        generation += 1
        let gen = generation
        let q = trimmed
        guard !q.isEmpty else {
            hits = []
            loading = false
            return
        }
        loading = true
        try? await Task.sleep(nanoseconds: 150_000_000)
        guard !Task.isCancelled, gen == generation else { return }
        let found = await Task.detached(priority: .userInitiated) {
            Cities.matcher.searchHits(q, limit: 12)
        }.value
        guard !Task.isCancelled, gen == generation else { return }
        hits = found
        loading = false
    }
}
