// from
// http://stackoverflow.com/questions/105034/how-to-create-a-guid-uuid-in-javascript/105074#105074
// not a true guid but good enough for now
function s4() {
  return Math.floor((1 + Math.random()) * 0x10000)
             .toString(16)
             .substring(1);
};

function guid() {
  return s4() + s4() + '-' + s4() + '-' + s4() + '-' +
         s4() + '-' + s4() + s4() + s4();
};

function initEventSource(clientId) {
  if (typeof EventSource === 'undefined') {
    alert("Your browser doesn't support html5 server-sent events and will not work properly here. For browsers to use, see http://caniuse.com/#feat=eventsource.")
    return;
  }

  var es = new EventSource('/links?id='+clientId);
  es.addEventListener('results', function(e) {
    $("#results").append(e.data + "\n");
  });
  es.addEventListener('end-message', function(e) {
    window.history.pushState({"message": $("#message").html(),
                              "title": document.title,
                              "results": $("#results").html()},
                             null, e.data);
    $('#results-control').on('click', function(e) { $('tr.success').toggle(); });
    $('#results-control').show();
    $('tr.success').hide();
  });
  es.onmessage = function(e) {
    $('#message').html(e.data + "\n");
  };
  es.onerror = function(e) {
    $('.alert-box').show();
    $('#error').html(e.data);
    $('#message').hide();
  };
  return es;
};

var clientId = guid();
var es = initEventSource(clientId);

$(function() {
  window.addEventListener("popstate", function(e) {
    // guard against initial page load in chrome
    if (e.state) {
      $("#message").html(e.state.message);
      $("#results").html(e.state.results);
      $("h2.title").html(e.state.title);
      document.title = e.state.title;
    }
  });

  var fetchLinks = function(url, selector) {
    $.post('/links', {id: clientId, url: url, selector: selector});
    $('#results').show();
    $('#results-control').hide();
    $('#message').show();
    $('#message').html('Fetching links... <img src=\'/images/spinner.gif\' />');
    $('#url').val('');
    $('#selector').val('');
    $('tbody').html('');
    document.title = "Links for " + url + (selector ? " with selector " + selector : "");
    $('h2.title').html(document.title);
  };

  $("form").on('submit', function(e) {
    fetchLinks($("#url").val(), $("#selector").val());
    e.preventDefault();
  });

  // close alert box
  $('a.close').on('click', function(e) { $(e.target).parent().hide() });

  $('a.url').on('click', function(e) {
    fetchLinks(e.target.text, null);
    e.preventDefault;
  });

  if (!(window.history && window.history.pushState)) {
    alert("Your browser doesn't support html5 history and may not work properly here. For browsers to use, see http://caniuse.com/#feat=history .")
    return;
  }

  var match;
  if (match = window.location.search.match(/url=([^&]+)/)) {
    var selector = window.location.search.match(/selector=([^&]+)/);
    // Allow time for sse to register
    setTimeout(function() { fetchLinks(match[1], selector ? selector[1] : null) },
               500);
  } else {
    window.history.pushState({"message": '', "results": '', "title": $("h2.title").html()},
                             null,
                             '');
  }
});
