package com.celzero.bravedns.ui.compose.appdetails
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.stats.StatsInsightsMath
import com.celzero.bravedns.util.UIUtils.getCountryNameFromFlag

@Composable fun AppDetailsInsights(section:AppInsightsSection,items:List<AppConnection>) {
    val fractions=StatsInsightsMath.normalizeFractions(items.map{it.count.toLong()})
    val good=themeColor(R.attr.accentGood);val bad=themeColor(R.attr.accentBad);val track=themeColor(R.attr.colorSurfaceContainerHighest)
    val base=if(section.blocked)bad else good
    val rows:@Composable ()->Unit={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items.take(5).forEachIndexed { i,item ->
                val label=when(section) {
                    AppInsightsSection.ACTIVE_CONNS,AppInsightsSection.IPS,AppInsightsSection.BLOCKED_IPS -> item.ipAddress
                    AppInsightsSection.DOMAINS,AppInsightsSection.BLOCKED_DOMAINS -> item.appOrDnsName?.dropLastWhile{it=='.'}.orEmpty()
                    AppInsightsSection.COUNTRIES -> getCountryNameFromFlag(item.flag).takeIf{it.isNotEmpty()&&!it.all{c->c=='-'}}?:stringResource(R.string.two_argument_space,stringResource(R.string.network_log_app_name_unknown),item.flag)
                    AppInsightsSection.ASN,AppInsightsSection.BLOCKED_ASN -> stringResource(R.string.two_argument_space,item.flag,item.appOrDnsName.orEmpty())
                    AppInsightsSection.BLOCKLISTS -> item.appOrDnsName.orEmpty()
                }
                val description=stringResource(R.string.ci_desc,label,item.count.toString())
                Column(Modifier.semantics(mergeDescendants=true){contentDescription=description}) {
                    Row { Text(label,Modifier.weight(1f),fontSize=12.sp);Text(item.count.toString(),Modifier.padding(start=10.dp),fontSize=12.sp) }
                    LinearProgressIndicator(progress={(fractions[i]*100).toInt()/100f},modifier=Modifier.fillMaxWidth().padding(top=5.dp).height(3.dp),color=if(!section.blocked&&item.blocked)bad else base,trackColor=track,gapSize=0.dp,drawStopIndicator={})
                }
            }
        }
    }
    if(section==AppInsightsSection.COUNTRIES) Column(Modifier.padding(horizontal=16.dp)){AppCountryMap(items);Spacer(Modifier.height(6.dp));rows()}
    else Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        val title=stringResource(section.title);val total=items.sumOf{it.count.toLong()}.toString()
        Box(Modifier.size(96.dp).semantics(mergeDescendants=true){contentDescription="$title: $total"},contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val width=minOf(size.width,size.height)*.14f;val diameter=minOf(size.width,size.height)-width
                val pos=Offset((size.width-diameter)/2,(size.height-diameter)/2);val area=Size(diameter,diameter)
                drawArc(track,0f,360f,false,pos,area,style=Stroke(width))
                var start=-90f;val alpha=listOf(255,190,140,100,70)
                appDonutSweeps(fractions.take(5)).forEachIndexed { i,sweep ->
                    if(sweep>2f)drawArc(base.copy(alpha=alpha[i]/255f),start,sweep-1.5f,false,pos,area,style=Stroke(width))
                    start+=sweep
                }
            }
            Text(total,fontSize=12.sp,color=themeColor(R.attr.primaryTextColor))
        }
        Spacer(Modifier.width(14.dp));Box(Modifier.weight(1f)){rows()}
    }
}
