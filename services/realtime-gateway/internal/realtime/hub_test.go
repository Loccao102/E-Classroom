package realtime

import "testing"

func TestParseRecipients(t *testing.T){payload:=[]byte(`{"eventId":"1","eventType":"notification.created","recipients":["u1","u2"]}`);got:=ParseRecipients(payload);if len(got)!=2||got[0]!="u1"{t.Fatalf("unexpected recipients %#v",got)}}
