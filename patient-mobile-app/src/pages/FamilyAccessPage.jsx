import { useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { BellRing, Save, Trash2, Users } from 'lucide-react'
import portalService from '@/services/portalService'
import useApiData from '@/hooks/useApiData'

export default function FamilyAccessPage() {
  const { data: proxies, refetch: refetchProxies } = useApiData(() => portalService.getProxies(), [])
  const { data: proxyAccess } = useApiData(() => portalService.getProxyAccess(), [])
  const { data: rawPrefs, refetch: refetchPrefs } = useApiData(() => portalService.getNotificationPreferences(), [])
  const [drafts, setDrafts] = useState({})

  const preferences = useMemo(() => (Array.isArray(rawPrefs) ? rawPrefs : []).map((item) => ({
    ...item,
    enabled: drafts[item.id] ?? item.enabled ?? true,
  })), [rawPrefs, drafts])

  const handleSave = async (pref) => {
    await portalService.setNotificationPreference({
      notificationType: pref.notificationType || pref.type,
      channel: pref.channel,
      enabled: pref.enabled,
    })
    await refetchPrefs()
  }

  const handleRevoke = async (proxyId) => {
    await portalService.revokeProxy(proxyId)
    await refetchProxies()
  }

  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-2xl font-bold text-slate-900">Family access & alerts</h2>
        <p className="mt-1 text-sm text-slate-500">Manage proxy access and tune the notification channels used by the mobile app.</p>
      </div>

      <Section title="People who can access my chart" icon={Users}>
        {(Array.isArray(proxies) ? proxies : []).map((proxy) => (
          <Card key={proxy.id}>
            <CardContent className="flex items-start justify-between gap-3 p-4">
              <div>
                <h3 className="font-semibold text-slate-900">{proxy.proxyName || proxy.fullName || proxy.username || 'Proxy user'}</h3>
                <p className="text-sm text-slate-500">{proxy.relationship || proxy.accessLevel || proxy.email || 'Family / caregiver access'}</p>
              </div>
              <Button size="sm" variant="outline" className="text-red-600" onClick={() => handleRevoke(proxy.id)}>
                <Trash2 className="mr-1 h-4 w-4" /> Revoke
              </Button>
            </CardContent>
          </Card>
        ))}
      </Section>

      <Section title="Charts I can access" icon={Users}>
        {(Array.isArray(proxyAccess) ? proxyAccess : []).map((entry) => (
          <Card key={entry.id}>
            <CardContent className="p-4">
              <h3 className="font-semibold text-slate-900">{entry.patientName || entry.fullName || 'Proxy access'}</h3>
              <p className="text-sm text-slate-500">{entry.relationship || entry.accessLevel || entry.email || 'Shared access record'}</p>
            </CardContent>
          </Card>
        ))}
      </Section>

      <Section title="Notification preferences" icon={BellRing}>
        {preferences.map((pref) => (
          <Card key={pref.id || `${pref.notificationType}-${pref.channel}`}>
            <CardContent className="space-y-3 p-4">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <h3 className="font-semibold text-slate-900">{pref.notificationType || pref.type}</h3>
                  <p className="text-sm text-slate-500">Channel: {pref.channel || 'Push'}</p>
                </div>
                <Badge className={pref.enabled ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-100 text-slate-700'}>{pref.enabled ? 'Enabled' : 'Muted'}</Badge>
              </div>
              <div className="flex items-center gap-3">
                <Input value={pref.enabled ? 'Enabled' : 'Muted'} readOnly className="max-w-[110px]" />
                <Button variant="outline" size="sm" onClick={() => setDrafts((current) => ({ ...current, [pref.id]: !pref.enabled }))}>
                  Toggle
                </Button>
                <Button size="sm" className="bg-blue-700 hover:bg-blue-800" onClick={() => handleSave({ ...pref, enabled: drafts[pref.id] ?? pref.enabled })}>
                  <Save className="mr-1 h-4 w-4" /> Save
                </Button>
              </div>
            </CardContent>
          </Card>
        ))}
        <Button variant="outline" onClick={() => portalService.resetNotificationPreferences().then(refetchPrefs)}>
          Reset preferences
        </Button>
      </Section>
    </div>
  )
}

function Section({ title, icon, children }) {
  const Glyph = icon
  const items = Array.isArray(children) ? children.filter(Boolean) : [children]
  return (
    <section className="space-y-3">
      <div className="flex items-center gap-2">
        <Glyph className="h-4 w-4 text-blue-600" />
        <h3 className="font-semibold text-slate-900">{title}</h3>
      </div>
      {items.length > 0 ? items : <Card><CardContent className="p-4 text-sm text-slate-500">Nothing available yet.</CardContent></Card>}
    </section>
  )
}
