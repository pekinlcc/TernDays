package app.terndays.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.terndays.android.geo.Cities
import app.terndays.core.CityMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 选城市(更正 / 补记 / 阈值共用,此前两处各写一份):
 * 空查询显示建议城市;输入后 150ms 防抖再搜,搜索期间显示加载态,没有结果时给出换写法的提示。
 * 打开即在后台预热城市库(首次解析 3.4 万行要几百毫秒)。
 */
@Composable
fun CityPicker(
    suggestions: List<Pair<String, String>>,
    suggestionTitle: String = "常去城市",
    maxHeight: Dp = 260.dp,
    onPick: (key: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Cities.get(context) } }
    // null = 正在搜
    val results by produceState<List<CityMatcher.SearchHit>?>(emptyList(), query) {
        val q = query.trim()
        if (q.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        value = null
        delay(150)
        value = withContext(Dispatchers.IO) { Cities.get(context).search(q, 12) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text("搜索城市名（支持拼音 / 英文）", fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        val blank = query.isBlank()
        if (blank && suggestions.isNotEmpty()) {
            Text(suggestionTitle, fontSize = 11.sp, color = Td.Faint)
        }
        val hits = results
        when {
            !blank && hits == null -> Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Td.Accent, strokeWidth = 2.dp)
            }
            !blank && hits.isNullOrEmpty() -> Text(
                "没有匹配的城市，可换拼音或英文名试试",
                fontSize = 13.sp, color = Td.Muted, modifier = Modifier.padding(vertical = 12.dp),
            )
            else -> LazyColumn(Modifier.heightIn(max = maxHeight)) {
                if (blank) {
                    items(suggestions.distinctBy { it.first }, key = { it.first }) { (key, name) ->
                        PickRow(name, "") { onPick(key, name) }
                    }
                } else {
                    items(hits.orEmpty(), key = { it.cityKey }) { hit ->
                        PickRow(hit.cityName, hit.region) { onPick(hit.cityKey, hit.cityName) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickRow(name: String, region: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontSize = 14.sp, color = Td.Ink)
        if (region.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(region, fontSize = 12.sp, color = Td.Faint)
        }
    }
}
